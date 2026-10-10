var SHIFTS_SHEET = "Shifts";
var PLACES_SHEET = "Places";

/**
 * Column L (חישוב משכורת) formula, in R1C1 so it can be set on any row.
 * Billed hours = worked hours (FLL, capped at P3) or half the worked hours
 * (FRC), rounded to the nearest quarter-hour, times the hourly rate.
 * The values live in the Shifts sheet itself: P1 = FLL rate, P2 = FRC rate,
 * P3 = max billed FLL hours per lesson.
 * Change them there, not here.
 */
var SALARY_FORMULA_R1C1 =
  '=IF(ISNUMBER(RC5),IF(ISNUMBER(SEARCH("frc",RC4&RC8)),' +
  'MROUND(RC5/2,0.25)*R2C16,MIN(MROUND(RC5,0.25),R3C16)*R1C16),"")';

/**
 * Handles GET requests to fetch the list of places dynamically.
 */
function doGet(e) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(PLACES_SHEET);
  var data = sheet.getRange("A1:A").getValues();
  var places = [];

  for (var i = 0; i < data.length; i++) {
    if (data[i][0] !== "") {
      places.push(data[i][0]);
    }
  }

  return ContentService.createTextOutput(JSON.stringify(places))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * True if this shift is FRC work, based on either the place or the
 * meeting type containing "frc" (case-insensitive). Either one counts.
 * Also used by sync.gs.
 */
function isFrcShift(place, meetingType) {
  var p = (place || "").toString().toLowerCase();
  var m = (meetingType || "").toString().toLowerCase();
  return p.indexOf("frc") !== -1 || m.indexOf("frc") !== -1;
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(30000);
    var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHIFTS_SHEET);
    var payload = JSON.parse(e.postData.contents);

    // The app retries a POST when the answer is slow, even if this script
    // already saved it. Treat a row with the same date, times and place as
    // the same lesson and report success without appending it again.
    if (isAlreadySaved(sheet, payload)) {
      return ContentService.createTextOutput(JSON.stringify({"status": "success", "duplicate": true}))
        .setMimeType(ContentService.MimeType.JSON);
    }

    // Function to handle empty fields
    var clean = function(val) {
      return (val === null || val === undefined || String(val).trim() === "") ? "ריק" : val;
    };

    // Appends the data with the full 12-column structure
    sheet.appendRow([
      payload.date,                // Column A: Date (e.g., 15.9.26)
      payload.entryTime,           // Column B: Entry Time
      payload.leaveTime,           // Column C: Leave Time
      payload.place,               // Column D: Place
      payload.duration,            // Column E: Duration
      clean(payload.notes1),       // Column F: Task Description
      clean(payload.notes2),       // Column G: Additional Notes
      clean(payload.meetingType),  // Column H: סוג מפגש
      clean(payload.dediLed),      // Column I: דדי הוביל
      clean(payload.studentCount), // Column J: מספר תלמידים שהגיעו
      "",                          // Column K: (skipped - left blank)
      ""                           // Column L: set below as a formula
    ]);
    sheet.getRange(sheet.getLastRow(), 12).setFormulaR1C1(SALARY_FORMULA_R1C1);

    return ContentService.createTextOutput(JSON.stringify({"status": "success"}))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (error) {
    return ContentService.createTextOutput(JSON.stringify({"status": "error", "message": error.toString()}))
      .setMimeType(ContentService.MimeType.JSON);
  } finally {
    lock.releaseLock();
  }
}

/** How many of the most recent rows to check for a repeated submission. */
var DUPLICATE_LOOKBACK_ROWS = 50;

function isAlreadySaved(sheet, payload) {
  var lastRow = sheet.getLastRow();
  if (lastRow < 2) return false;
  var first = Math.max(2, lastRow - DUPLICATE_LOOKBACK_ROWS + 1);
  var rows = sheet.getRange(first, 1, lastRow - first + 1, 4).getDisplayValues();
  var key = [payload.date, payload.entryTime, payload.leaveTime, payload.place]
    .map(function(v) { return String(v || "").trim(); }).join("|");
  for (var i = 0; i < rows.length; i++) {
    var rowKey = rows[i].map(function(v) { return String(v).trim(); }).join("|");
    if (rowKey === key) return true;
  }
  return false;
}

/**
 * Updates dropdown validation.
 * Note: Place has moved to Column D.
 * Re-run after adding rows to the Places sheet.
 */
function setupDataValidation() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var shiftsSheet = ss.getSheetByName(SHIFTS_SHEET);
  var placesSheet = ss.getSheetByName(PLACES_SHEET);

  var lastRow = placesSheet.getLastRow();
  if (lastRow < 1) lastRow = 1;

  var range = placesSheet.getRange(1, 1, lastRow, 1);

  var rule = SpreadsheetApp.newDataValidation()
    .requireValueInRange(range, true)
    .setAllowInvalid(false)
    .build();

  // Apply to Column D (Place) now that Date was added as Column A
  var shiftDataRange = shiftsSheet.getRange("D2:D1000");
  shiftDataRange.setDataValidation(rule);
}
