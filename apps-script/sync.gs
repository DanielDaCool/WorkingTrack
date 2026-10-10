/**
 * ============================================================================
 *  סנכרון  "שעות עבודה מהאפליקציה" (Shifts)  ->  "דיווח פעילות 2026-2027"
 * ============================================================================
 *  התקנה:
 *    1. פתח את "שעות עבודה מהאפליקציה" (הקובץ שבבעלותך).
 *    2. Extensions > Apps Script. החלף את תוכן sync.gs בקובץ הזה, שמור.
 *       (appBackend.gs נשאר כמו שהוא - isFrcShift מוגדר שם.)
 *    3. רענן את הגיליון. יופיע תפריט "סנכרון דיווח".
 *    4. הרץ "בדיקה בלבד" ובדוק את "יומן סנכרון".
 *    5. הרץ "הפעל סנכרון יומי אוטומטי" פעם אחת ואשר הרשאות.
 *
 *  מה הסקריפט לא עושה לעולם:
 *    - לא כותב לעמודה A (סימון ה-X של איריס) ולא לעמודה B.
 *    - לא נוגע בשורה 2 (שורת הדוגמה).
 *    - לא משנה שורה שמסומנת ב-X.
 *    - לא מוחק שורה ביעד שהסקריפט עצמו לא סנכרן קודם (שורות שהוקלדו ידנית בטוחות).
 *    - לא דורס נוסחאות.
 *    - לא כותב ערך שאינו ברשימה הנפתחת של אותה עמודה.
 * ============================================================================
 */

// ============================ CONFIG ========================================

var CONFIG = {
  DEST_FILE_ID: '1xmhy0gJ5i7CC9f3q1rt0kMUgyT3nMITqRpplNyWBFJM',
  DEST_SHEET:   'דיווח פעילות',
  SRC_SHEET:    'Shifts',

  INSTRUCTOR_NAME: 'דניאל',

  // עיגול שעות -> מספר שיעורים.
  // 'none' | 'nearest_half' | 'nearest_quarter' | 'floor_half' | 'ceil_half'
  // | 'nearest_whole' | 'blank'
  BILLING_ROUNDING: 'nearest_quarter',   // עמודה L, לחיוב הלקוח
  ACTUAL_ROUNDING:  'nearest_quarter',   // עמודה M, בפועל

  // עמודה O. הכותרת ביעד שואלת "שלא הגיעו או כמה הגיעו", לכן מוסיפים מילה
  // כדי שלא יתפרש הפוך. '' = לכתוב רק את המספר.
  ATTENDANCE_PREFIX: 'הגיעו ',

  // מה נכתב בעמודה I (סוג מפגש) למשמרת FRC. חייב להיות ברשימה הנפתחת ביעד,
  // אחרת הכתיבה נחסמת ונרשמת ביומן. '' = להשאיר ריק.
  FRC_MEETING_TYPE: 'frc- בבדיקה',

  // שעת הסנכרון היומי האוטומטי (0-23, שעון ישראל).
  DAILY_SYNC_HOUR: 23,

  // מקסימום שעות לחיוב הלקוח (עמודה L) בשיעור FLL / לא-FRC. ערך שהוקלד ידנית
  // בעמודה L ביעד לא נדרס, כך שאפשר לחרוג מהתקרה ידנית.
  FLL_MAX_BILLED_HOURS: 4,

  DRY_RUN: false
};

/** מקום במקור -> יישוב / בית ספר / כיתה ביעד. */
var PLACE_MAP = {
  'תל אביב (fll)': { yishuv: 'תל-אביב',   school: "עירוני ה'",       grade: 'ז - FLL' },
  'תל אביב (frc)': { yishuv: 'תל-אביב',   school: "עירוני ה'",       grade: 'ט-י"ב - FRC' },
  'ברנר':          { yishuv: 'גבעת ברנר', school: 'תיכון מקיף ברנר', grade: 'ח - FLL' },
  // נס ציונה - FLL. grade ריק = הסקריפט יזהיר ביומן ולא ימלא את עמודה H.
  'נס ציונה (בן צבי)': { yishuv: 'נס ציונה', school: 'בן צבי', grade: '' },   // <<< כיתה
  'נס ציונה (שקד)':    { yishuv: 'נס ציונה', school: 'שקד',    grade: '' },   // <<< כיתה
  'נס ציונה (ארגמן)':  { yishuv: 'נס ציונה', school: 'ארגמן',  grade: '' }    // <<< כיתה
};

/** עמודה I ביעד מקבלת שיעור/תיגבור/אחר. FRC מטופל בנפרד דרך CONFIG.FRC_MEETING_TYPE. */
var MEETING_TYPE_MAP = {
  'שיעור': 'שיעור', 'תיגבור': 'תיגבור', 'תגבור': 'תיגבור',
  'אחר': 'אחר', 'fll': ''
};

/** האפליקציה כותבת את המילה "ריק" במקום להשאיר תא ריק. אלה נחשבים ריקים. */
var BLANK_TOKENS = ['ריק', 'ריק.', '-', 'n/a', 'na', 'none', 'null'];

/** גיבוי בלבד. בפועל נקרא מאימות הנתונים של עמודה N ביעד. */
var DEDI_FALLBACK = ['הוביל את השיעור', 'נכח בשיעור', 'לא היה',
  'אירים באה לתגבר', 'אירים החליפה אותי', 'מני החליף אותי'];

/** מפתחות (תאריך|שעה) של שורות שהסקריפט סנכרן. רק הן יכולות להימחק כיתומות. */
var OWNED_KEYS_PROP = 'OWNED_KEYS';

/** קידומת ל-Script Properties: הערך האחרון שהסקריפט כתב לעמודה L בכל שורה (מפתח תאריך|שעה).
 *  ערך ביעד ששונה ממנו = עריכה ידנית, ולא נדרס. */
var WRITTEN_L_PREFIX = 'L|';

// ======================= מבנה גיליון היעד ===================================

var COL = { A:1,B:2,C:3,D:4,E:5,F:6,G:7,H:8,I:9,J:10,K:11,L:12,M:13,N:14,O:15,P:16,Q:17 };
var LAST_COL = 17;
var HEADER_ROW = 1;
var FIRST_DATA_ROW = 3;                 // שורה 2 = שורת הדוגמה של איריס

/** מסונכרן בכל הרצה. המקור הוא מקור האמת. */
var SYNC_COLS = ['C','D','E','F','G','J','K','L','M','N','O','P','Q'];
/** נכתב פעם אחת כשהתא ריק ואחר כך לא נוגעים. דורש מידע שאין במקור. */
var SEED_ONLY = ['H','I'];
/** עמודות שנבדקות מול הרשימה הנפתחת שלהן ביעד. */
var VALIDATED = ['F','G','I','N'];

var HEB_DAYS = ['א','ב','ג','ד','ה','ו','ש'];

// ============================ תפריט =========================================

function onOpen() {
  SpreadsheetApp.getUi().createMenu('סנכרון דיווח')
    .addItem('בדיקה בלבד (לא כותב כלום)', 'runDryRun')
    .addItem('סנכרן עכשיו', 'runSync')
    .addSeparator()
    .addItem('הפעל סנכרון יומי אוטומטי', 'installDailyTrigger')
    .addItem('בטל סנכרון יומי אוטומטי', 'removeDailyTrigger')
    .addToUi();
}
function runDryRun() { execute(true); }
function runSync()   { execute(CONFIG.DRY_RUN); }
/** מופעל ע"י הטריגר היומי. */
function runScheduledSync() { execute(false); }

// ============================ טריגר יומי ====================================

function installDailyTrigger() {
  deleteSyncTriggers();
  ScriptApp.newTrigger('runScheduledSync')
    .timeBased().everyDays(1).atHour(CONFIG.DAILY_SYNC_HOUR)
    .inTimezone('Asia/Jerusalem').create();
  notify('סנכרון יומי הופעל - כל יום בסביבות ' + CONFIG.DAILY_SYNC_HOUR + ':00.');
}

function removeDailyTrigger() {
  var n = deleteSyncTriggers();
  notify(n ? 'הסנכרון היומי בוטל.' : 'לא היה סנכרון יומי פעיל.');
}

function deleteSyncTriggers() {
  var n = 0, all = ScriptApp.getProjectTriggers();
  for (var i = 0; i < all.length; i++) {
    if (all[i].getHandlerFunction() === 'runScheduledSync') { ScriptApp.deleteTrigger(all[i]); n++; }
  }
  return n;
}

function notify(msg) {
  try { SpreadsheetApp.getUi().alert(msg); } catch (e) { Logger.log(msg); }
}

// ============================ עזרים =========================================

function pad2(n) { return (n < 10 ? '0' : '') + n; }

function normKey(s) {
  if (s === null || s === undefined) return '';
  return String(s)
    .replace(/[‎‏‪-‮]/g, '')
    .replace(/[־‐-―]/g, '-')
    .replace(/[׳'"״]/g, '')
    .replace(/\s+/g, ' ')
    .replace(/\s*-\s*/g, '-')
    .trim().toLowerCase();
}

function isBlankish(v) {
  if (v === null || v === undefined) return true;
  var s = String(v).trim();
  if (s === '') return true;
  var n = normKey(s);
  for (var i = 0; i < BLANK_TOKENS.length; i++) if (normKey(BLANK_TOKENS[i]) === n) return true;
  return false;
}
function isBlank(v) { return v === null || v === undefined || String(v).trim() === ''; }
function cleanVal(v) { return isBlankish(v) ? '' : String(v).trim(); }

function lookupPlace(raw) {
  var k = normKey(raw), key;
  for (key in PLACE_MAP) if (normKey(key) === k) return PLACE_MAP[key];
  var loose = k.replace(/-/g,' ').replace(/\s+/g,' ');
  for (key in PLACE_MAP) {
    if (normKey(key).replace(/-/g,' ').replace(/\s+/g,' ') === loose) return PLACE_MAP[key];
  }
  return null;
}

function mkDate(y, mo, d) {
  if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
  var dt = new Date(y, mo - 1, d);
  if (dt.getFullYear() !== y || dt.getMonth() !== mo - 1 || dt.getDate() !== d) return null;
  return dt;
}

function parseDateValue(v) {
  if (Object.prototype.toString.call(v) === '[object Date]' && !isNaN(v.getTime())) {
    return new Date(v.getFullYear(), v.getMonth(), v.getDate());
  }
  if (isBlankish(v)) return null;
  var s = String(v).trim(), m;
  m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
  if (m) return mkDate(+m[1], +m[2], +m[3]);
  m = s.match(/^(\d{1,2})[.\/\-](\d{1,2})[.\/\-](\d{2,4})$/);
  if (m) { var y = +m[3]; if (y < 100) y += 2000; return mkDate(y, +m[2], +m[1]); }
  return null;
}

function parseTimeValue(v) {
  if (Object.prototype.toString.call(v) === '[object Date]' && !isNaN(v.getTime())) {
    return pad2(v.getHours()) + ':' + pad2(v.getMinutes());
  }
  if (isBlankish(v)) return null;
  if (typeof v === 'number') {
    if (v < 0 || v >= 1) return null;
    var mins = Math.round(v * 1440);
    return pad2(Math.floor(mins / 60)) + ':' + pad2(mins % 60);
  }
  var m = String(v).trim().match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!m) return null;
  var h = +m[1], mi = +m[2];
  if (h > 23 || mi > 59) return null;
  return pad2(h) + ':' + pad2(mi);
}

function fmtDate(dt) { return pad2(dt.getDate()) + '/' + pad2(dt.getMonth()+1) + '/' + dt.getFullYear(); }
function rowKey(dt, t) { return fmtDate(dt) + '|' + t; }
function hebrewWeekday(dt) { return HEB_DAYS[dt.getDay()]; }

function colLetter(n) {
  var s = '';
  while (n > 0) { var r = (n-1) % 26; s = String.fromCharCode(65+r) + s; n = (n-r-1)/26; }
  return s;
}

// isFrcShift(place, meetingType) is defined once, in appBackend.gs,
// since both files live in the same Apps Script project.
// Do not redefine it here - a duplicate top-level function name across
// files in one project is undefined behavior in Apps Script.

function roundLessons(hours, mode) {
  if (hours === null || hours === '') return '';
  var h = Number(hours);
  if (!isFinite(h)) return '';
  switch (mode) {
    case 'blank':           return '';
    case 'nearest_half':    return Math.round(h*2)/2;
    case 'nearest_quarter': return Math.round(h*4)/4;
    case 'floor_half':      return Math.floor(h*2)/2;
    case 'ceil_half':       return Math.ceil(h*2)/2;
    case 'nearest_whole':   return Math.round(h);
    default:                return Math.round(h*100)/100;
  }
}

function mapMeetingType(raw, isFrc) {
  if (isFrc) return { value: CONFIG.FRC_MEETING_TYPE, warn: null };
  var c = cleanVal(raw);
  if (!c) return { value:'', warn:null };
  var n = normKey(c);
  for (var k in MEETING_TYPE_MAP) {
    if (normKey(k) === n) {
      return MEETING_TYPE_MAP[k]
        ? { value: MEETING_TYPE_MAP[k], warn: null }
        : { value:'', warn:'סוג מפגש "' + c + '" אינו שיעור/תיגבור - עמודה I נשארת ריקה' };
    }
  }
  return { value:'', warn:'סוג מפגש לא מוכר: "' + c + '" - עמודה I נשארת ריקה' };
}

function mapDedi(raw, allowed) {
  var c = cleanVal(raw);
  if (!c) return { value:'', warn:null };
  var list = (allowed && allowed.length) ? allowed : DEDI_FALLBACK;
  for (var i = 0; i < list.length; i++) {
    if (normKey(list[i]) === normKey(c)) return { value:list[i], warn:null };
  }
  return { value:'', warn:'ערך "דדי הוביל" לא קיים ברשימה ביעד: "' + c + '" - לא נכתב' };
}

function mapAttendance(raw) {
  var c = cleanVal(raw);
  if (!c) return '';
  if (/^\d+$/.test(c)) return CONFIG.ATTENDANCE_PREFIX ? CONFIG.ATTENDANCE_PREFIX + Number(c) : Number(c);
  return c;
}

// ==================== המרת שורת מקור לרשומת יעד =============================

function buildRecord(src, dediAllowed) {
  var warn = [];
  var dt = parseDateValue(src.date);
  if (!dt) return { ok:false, warnings:['תאריך לא קריא: "' + src.date + '"'] };

  var from = parseTimeValue(src.from);
  if (!from) return { ok:false, warnings:['שעת כניסה לא קריאה: "' + src.from + '"'] };
  var to = parseTimeValue(src.to);
  if (!to) warn.push('שעת יציאה חסרה או לא קריאה');

  var place = lookupPlace(src.place);
  if (!place) {
    warn.push('מקום לא מוכר: "' + cleanVal(src.place) + '" - הוסף אותו ל-PLACE_MAP');
    place = { yishuv:'', school:'', grade:'' };
  } else {
    if (!place.school) warn.push('חסר בית ספר עבור "' + cleanVal(src.place) + '"');
    if (!place.grade)  warn.push('חסרה כיתה עבור "' + cleanVal(src.place) + '"');
  }

  var hRaw = cleanVal(src.hours);
  var hours = hRaw === '' ? null : Number(hRaw);
  if (hours !== null && !isFinite(hours)) { warn.push('זמן עבודה לא מספרי: "' + hRaw + '"'); hours = null; }
  if (hours === null) warn.push('זמן עבודה ריק - L/M יישארו ריקות');

  var isFrc = isFrcShift(src.place, src.meetingType);

  var mt = mapMeetingType(src.meetingType, isFrc);
  if (mt.warn) warn.push(mt.warn);
  if (!isFrc && !mt.value && hours !== null) {
    warn.push('נכתבות שעות ל-L/M אך סוג המפגש אינו שיעור/תיגבור/אחר - ודא שזה באמת לחיוב');
  }

  var dd = mapDedi(src.dedi, dediAllowed);
  if (dd.warn) warn.push(dd.warn);

  // FRC: בפועל (M) = כל השעות שעבד. לחיוב הלקוח (L) = מחצית.
  // FLL (כל מה שאינו FRC): לחיוב הלקוח (L) לא יותר מ-FLL_MAX_BILLED_HOURS.
  var billHours = (hours !== null && isFrc) ? hours * 0.5 : hours;
  var billed = billHours === null ? '' : roundLessons(billHours, CONFIG.BILLING_ROUNDING);
  if (!isFrc && billed !== '' && billed > CONFIG.FLL_MAX_BILLED_HOURS) {
    warn.push('FLL - ' + billed + ' שעות לחיוב הוגבלו ל-' + CONFIG.FLL_MAX_BILLED_HOURS + ' (עמודה L)');
    billed = CONFIG.FLL_MAX_BILLED_HOURS;
  }

  // עמודות שהמקור שלהן ריק ("ריק" או תא ריק) - ביעד הן מתרוקנות גם אם היה בהן ערך.
  // ערך שנחסם בגלל מיפוי (למשל "דדי הוביל" לא מוכר) לא מרוקן תא קיים.
  var clearable = {
    K: !to,
    L: hours === null, M: hours === null,
    N: isBlankish(src.dedi),
    O: isBlankish(src.kids),
    P: isBlankish(src.what),
    Q: isBlankish(src.remember)
  };

  return {
    ok: true, key: rowKey(dt, from), warnings: warn, clearable: clearable,
    values: {
      C: CONFIG.INSTRUCTOR_NAME,
      D: dt,
      E: hebrewWeekday(dt),
      F: place.yishuv,
      G: place.school,
      H: place.grade,
      I: mt.value,
      J: from,
      K: to || '',
      L: billed,
      M: hours === null ? '' : roundLessons(hours, CONFIG.ACTUAL_ROUNDING),
      N: dd.value,
      O: mapAttendance(src.kids),
      P: cleanVal(src.what),
      Q: cleanVal(src.remember)
    }
  };
}

// ==================== אימות מבנה ורשימות ביעד ===============================

function validateDestination(sheet) {
  var hdr = sheet.getRange(HEADER_ROW, 1, 1, LAST_COL).getValues()[0];
  var expect = [[COL.D,'תאריך'],[COL.E,'יום'],[COL.F,'יישוב'],[COL.G,'בית ספר'],
    [COL.H,'כיתה'],[COL.I,'סוג מפגש'],[COL.J,'משעה'],[COL.K,'עד'],[COL.L,'לחיוב'],
    [COL.M,'בפועל'],[COL.N,'דדי'],[COL.O,'תלמידים'],[COL.P,'הפעילות'],[COL.Q,'לשבוע הבא']];
  var bad = [];
  for (var i = 0; i < expect.length; i++) {
    if (normKey(hdr[expect[i][0]-1]).indexOf(normKey(expect[i][1])) === -1) {
      bad.push('עמודה ' + colLetter(expect[i][0]) + ' אמורה להכיל "' + expect[i][1] +
               '" אך מכילה "' + hdr[expect[i][0]-1] + '"');
    }
  }
  if (bad.length) {
    throw new Error('מבנה גיליון היעד השתנה. הסנכרון בוטל כדי לא לכתוב לעמודה הלא נכונה.\n' +
      bad.join('\n'));
  }
}

/** קורא את הערכים המותרים מהרשימה הנפתחת של עמודה, אם קיימת. */
function readValidation(sheet, col, probeRow) {
  try {
    var rule = sheet.getRange(probeRow, col).getDataValidation();
    if (!rule) return null;
    var type = rule.getCriteriaType();
    var args = rule.getCriteriaValues();
    if (type === SpreadsheetApp.DataValidationCriteria.VALUE_IN_LIST) {
      return args[0].map(function (x) { return String(x).trim(); });
    }
    if (type === SpreadsheetApp.DataValidationCriteria.VALUE_IN_RANGE) {
      return args[0].getValues()
        .map(function (r) { return String(r[0]).trim(); })
        .filter(function (x) { return x !== ''; });
    }
    return null;
  } catch (e) { return null; }
}

// ==================== מפתחות בבעלות הסקריפט =================================

function loadOwnedKeys() {
  var raw = PropertiesService.getScriptProperties().getProperty(OWNED_KEYS_PROP);
  var set = {};
  if (raw) {
    var arr = JSON.parse(raw);
    for (var i = 0; i < arr.length; i++) set[arr[i]] = true;
  }
  return set;
}

function saveOwnedKeys(set) {
  PropertiesService.getScriptProperties().setProperty(OWNED_KEYS_PROP, JSON.stringify(Object.keys(set)));
}

function loadWrittenL() {
  var all = PropertiesService.getScriptProperties().getProperties();
  var out = {};
  for (var k in all) {
    if (k.indexOf(WRITTEN_L_PREFIX) === 0) out[k.substring(WRITTEN_L_PREFIX.length)] = all[k];
  }
  return out;
}

/** שמירה כמאפיין נפרד לכל שורה (לא חוסם על מגבלת 9KB לערך). מפתחות שנעלמו מהמקור נמחקים. */
function saveWrittenL(map, liveKeys) {
  var props = PropertiesService.getScriptProperties();
  var toSet = {};
  for (var k in map) {
    if (liveKeys[k]) toSet[WRITTEN_L_PREFIX + k] = String(map[k]);
    else props.deleteProperty(WRITTEN_L_PREFIX + k);
  }
  props.setProperties(toSet, false);
}

// ============================ הרצה ==========================================

function execute(dryRun) {
  var lock = LockService.getDocumentLock();
  if (!lock.tryLock(30000)) throw new Error('הרצה אחרת פועלת כרגע. נסה שוב בעוד רגע.');

  try {
    var log = [];
    var ss  = SpreadsheetApp.getActive();
    var src = ss.getSheetByName(CONFIG.SRC_SHEET);
    if (!src) throw new Error('לא נמצא גיליון "' + CONFIG.SRC_SHEET + '".');

    var dst = SpreadsheetApp.openById(CONFIG.DEST_FILE_ID).getSheetByName(CONFIG.DEST_SHEET);
    if (!dst) throw new Error('לא נמצא גיליון "' + CONFIG.DEST_SHEET + '" בקובץ היעד.');
    validateDestination(dst);

    var probe = Math.max(dst.getLastRow(), FIRST_DATA_ROW);
    var validators = {};
    for (var vi = 0; vi < VALIDATED.length; vi++) {
      var vn = VALIDATED[vi];
      var list = readValidation(dst, COL[vn], probe) || readValidation(dst, COL[vn], 2);
      if (list) validators[vn] = list;
    }
    var dediAllowed = validators['N'] || null;

    // ---------- קריאת המקור לפי כותרות ----------
    var sLast = src.getLastRow();
    if (sLast < 2) { report(dryRun, ['אין נתונים ב-' + CONFIG.SRC_SHEET]); return; }
    var sVals = src.getRange(1, 1, sLast, src.getLastColumn()).getValues();
    var want = { date:'תאריך', from:'שעת כניסה', to:'שעת יציאה', place:'מקום',
                 hours:'זמן עבודה', what:'מה נעשה', remember:'דברים לזכור',
                 meetingType:'סוג מפגש', dedi:'דדי הוביל', kids:'כמות ילדים' };
    var optional = { meetingType:1, dedi:1, kids:1 };
    var idx = {};
    for (var w in want) {
      for (var c = 0; c < sVals[0].length; c++) {
        if (normKey(sVals[0][c]).indexOf(normKey(want[w])) !== -1) { idx[w] = c; break; }
      }
      if (idx[w] === undefined) {
        if (optional[w]) log.push('הערה: לא נמצאה עמודה "' + want[w] + '" במקור. תדולג.');
        else throw new Error('לא נמצאה עמודה "' + want[w] + '" בגיליון ' + CONFIG.SRC_SHEET);
      }
    }
    var get = function (row, name) { return idx[name] === undefined ? '' : row[idx[name]]; };

    var records = [], seen = {}, nSkipped = 0;
    for (var r = 1; r < sVals.length; r++) {
      var row = sVals[r], sheetRow = r + 1;
      if (isBlankish(get(row,'date')) && isBlankish(get(row,'from')) && isBlankish(get(row,'place'))) continue;
      var rec = buildRecord({
        date: get(row,'date'), from: get(row,'from'), to: get(row,'to'),
        place: get(row,'place'), hours: get(row,'hours'), what: get(row,'what'),
        remember: get(row,'remember'), meetingType: get(row,'meetingType'),
        dedi: get(row,'dedi'), kids: get(row,'kids')
      }, dediAllowed);
      if (!rec.ok) { log.push('דילוג - מקור שורה ' + sheetRow + ': ' + rec.warnings.join('; ')); nSkipped++; continue; }
      if (seen[rec.key] !== undefined) {
        log.push('כפילות במקור - שורה ' + sheetRow + ' זהה לשורה ' + seen[rec.key] +
                 ' (' + rec.key + '). נלקחה הראשונה.');
        nSkipped++; continue;
      }
      seen[rec.key] = sheetRow;
      rec.srcRow = sheetRow;
      for (var q = 0; q < rec.warnings.length; q++) log.push('אזהרה - מקור ' + sheetRow + ': ' + rec.warnings[q]);
      records.push(rec);
    }

    // ---------- קריאת היעד ----------
    var dLast = Math.max(dst.getLastRow(), FIRST_DATA_ROW - 1);
    var nRows = Math.max(dLast - FIRST_DATA_ROW + 1, 0);
    var dVals = nRows > 0 ? dst.getRange(FIRST_DATA_ROW, 1, nRows, LAST_COL).getValues() : [];
    var dForm = nRows > 0 ? dst.getRange(FIRST_DATA_ROW, 1, nRows, LAST_COL).getFormulas() : [];

    var index = {};
    for (var i = 0; i < dVals.length; i++) {
      var dd2 = parseDateValue(dVals[i][COL.D-1]);
      var tt  = parseTimeValue(dVals[i][COL.J-1]);
      if (!dd2 || !tt) continue;
      var kk = rowKey(dd2, tt);
      if (index[kk] !== undefined) {
        log.push('שים לב - שתי שורות ביעד עם אותו מפתח ' + kk + ' (שורות ' +
                 (index[kk]+FIRST_DATA_ROW) + ' ו-' + (i+FIRST_DATA_ROW) + '). עודכנה הראשונה.');
      } else index[kk] = i;
    }

    // ---------- מחיקת שורות יתומות (לפני שיבוץ, כדי שהשורה תתפנה לשימוש חוזר) ----------
    // שורה נמחקת רק אם כל אלה מתקיימים:
    //   - השורה לא נעולה (אין X בעמודה A)
    //   - המפתח שלה (תאריך + שעת התחלה) סונכרן בעבר ע"י הסקריפט (OWNED_KEYS)
    //   - המפתח כבר לא קיים באף רשומת מקור נוכחית (השמרת נמחקה/שונתה באפליקציה)
    // שורה שהוקלדה ידנית ביעד לעולם לא נמצאת ב-OWNED_KEYS, ולכן לא תימחק.
    // נמחקים רק התאים C-Q, כך שעמודה B ונוסחאות בשורות אחרות לא נפגעות.
    var owned = loadOwnedKeys();
    var writtenL = loadWrittenL();
    var liveKeys = {};
    for (var lk = 0; lk < records.length; lk++) liveKeys[records[lk].key] = true;
    var orphanRows = [];
    for (var oi = 0; oi < dVals.length; oi++) {
      if (!isBlank(dVals[oi][COL.A-1])) continue;
      var od = parseDateValue(dVals[oi][COL.D-1]);
      var ot = parseTimeValue(dVals[oi][COL.J-1]);
      if (!od || !ot) continue;
      var ok2 = rowKey(od, ot);
      if (liveKeys[ok2]) continue;
      if (!owned[ok2]) {
        log.push('שורה ' + (oi+FIRST_DATA_ROW) + ' (' + ok2 + ') לא קיימת במקור ולא נוצרה ע"י הסקריפט - ' +
          'נשארת כמו שהיא.');
        continue;
      }
      log.push('נמחקה שורה יתומה ' + (oi+FIRST_DATA_ROW) + ' (' + ok2 + ') - כבר לא קיימת במקור.');
      for (var zc = COL.C-1; zc < LAST_COL; zc++) { dVals[oi][zc] = ''; dForm[oi][zc] = ''; }
      delete index[ok2];
      delete owned[ok2];
      orphanRows.push(oi + FIRST_DATA_ROW);
    }
    if (!dryRun && orphanRows.length) {
      for (var orr = 0; orr < orphanRows.length; orr++) {
        dst.getRange(orphanRows[orr], COL.C, 1, LAST_COL - COL.C + 1).clearContent();
      }
      SpreadsheetApp.flush();
    }

    function rowIsFree(i) {
      for (var c2 = 0; c2 < LAST_COL; c2++) if (!isBlank(dVals[i][c2])) return false;
      return true;
    }

    // ---------- שיבוץ ----------
    var edits = [], newRows = [], nCreated = 0, nUpdated = 0, nLocked = 0;
    var allCols = SYNC_COLS.concat(SEED_ONLY);

    for (var n2 = 0; n2 < records.length; n2++) {
      var rec2 = records[n2];
      var t = index[rec2.key];
      var isNew = (t === undefined);

      if (isNew) {
        for (var f = 0; f < dVals.length; f++) if (rowIsFree(f)) { t = f; break; }
        if (t === undefined) {
          var blank = [], blank2 = [];
          for (var z = 0; z < LAST_COL; z++) { blank.push(''); blank2.push(''); }
          dVals.push(blank); dForm.push(blank2);
          t = dVals.length - 1;
        }
      }

      if (!isBlank(dVals[t][COL.A-1])) {
        nLocked++;
        log.push('שורה נעולה (X) ביעד ' + (t+FIRST_DATA_ROW) + ' - לא שונתה. מקור שורה ' + rec2.srcRow + '.');
        continue;
      }

      var touched = false;
      for (var ci = 0; ci < allCols.length; ci++) {
        var name = allCols[ci], col = COL[name];
        var cur = dVals[t][col-1], nv = rec2.values[name];
        if (nv === undefined) continue;
        if (isBlank(nv) && !isBlank(cur) && !rec2.clearable[name]) continue;
        if (SEED_ONLY.indexOf(name) !== -1 && !isBlank(cur)) continue;
        if (!isBlank(dForm[t][col-1])) {
          log.push('נוסחה בתא ' + colLetter(col) + (t+FIRST_DATA_ROW) + ' - לא נדרסה.');
          continue;
        }
        var allow = validators[name];
        if (allow && !isBlank(nv) && allow.indexOf(String(nv)) === -1) {
          log.push('נחסם - "' + nv + '" אינו ברשימה הנפתחת של עמודה ' + colLetter(col) +
                   ' (שורה ' + (t+FIRST_DATA_ROW) + '). תקן את המיפוי.');
          continue;
        }
        if (name === 'L') {
          var prevL = writtenL[rec2.key];
          if (prevL !== undefined && !isBlank(cur) && !sameValue('L', cur, prevL)) {
            log.push('עמודה L בשורה ' + (t+FIRST_DATA_ROW) + ' נערכה ידנית (' + cur + ') - לא נדרסה.');
            continue;
          }
          writtenL[rec2.key] = nv;
        }
        if (sameValue(name, cur, nv)) continue;
        dVals[t][col-1] = nv;
        edits.push({ row: t+FIRST_DATA_ROW, col: col, val: nv });
        touched = true;
      }

      index[rec2.key] = t;
      owned[rec2.key] = true;
      if (isNew) { nCreated++; newRows.push(t+FIRST_DATA_ROW);
        log.push('חדש - מקור ' + rec2.srcRow + ' -> יעד ' + (t+FIRST_DATA_ROW)); }
      else if (touched) { nUpdated++;
        log.push('עודכן - מקור ' + rec2.srcRow + ' -> יעד ' + (t+FIRST_DATA_ROW)); }
    }

    // ---------- כתיבה ----------
    if (!dryRun) {
      for (var nr = 0; nr < newRows.length; nr++) {   // פורמט לפני ערך
        dst.getRange(newRows[nr], COL.D).setNumberFormat('dd/MM/yyyy');
        dst.getRange(newRows[nr], COL.J).setNumberFormat('HH:mm');
        dst.getRange(newRows[nr], COL.K).setNumberFormat('HH:mm');
      }
      for (var e = 0; e < edits.length; e++) {
        dst.getRange(edits[e].row, edits[e].col).setValue(edits[e].val);
      }
      SpreadsheetApp.flush();

      // רק מפתחות שעדיין קיימים במקור נשמרים, כדי שהרשימה לא תגדל ללא הגבלה.
      var keep = {};
      for (var ok in owned) if (liveKeys[ok]) keep[ok] = true;
      saveOwnedKeys(keep);
      saveWrittenL(writtenL, liveKeys);
    }

    log.unshift('רשומות תקינות: ' + records.length + ' | דילוגים: ' + nSkipped +
      ' | חדשות: ' + nCreated + ' | עודכנו: ' + nUpdated + ' | נעולות: ' + nLocked +
      ' | נמחקו (יתומות): ' + orphanRows.length + ' | תאים: ' + edits.length);
    log.unshift(dryRun ? '*** בדיקה בלבד. לא נכתב כלום. ***' : '*** סנכרון בוצע ***');
    report(dryRun, log);

  } finally { lock.releaseLock(); }
}

/** השוואה מודעת לסוג העמודה. בלעדיה הסקריפט היה כותב מחדש בכל הרצה. */
function sameValue(name, cur, nv) {
  if (isBlank(cur) && isBlank(nv)) return true;
  if (name === 'D') {
    var a = parseDateValue(cur), b = parseDateValue(nv);
    return !!a && !!b && fmtDate(a) === fmtDate(b);
  }
  if (name === 'J' || name === 'K') {
    var x = parseTimeValue(cur), y = parseTimeValue(nv);
    return !!x && !!y && x === y;
  }
  if (name === 'L' || name === 'M') {
    if (isBlank(cur) || isBlank(nv)) return false;
    var p = Number(cur), q = Number(nv);
    return isFinite(p) && isFinite(q) && Math.abs(p - q) < 1e-9;
  }
  return String(cur === null ? '' : cur).trim() === String(nv).trim();
}

function report(dryRun, log) {
  var ss = SpreadsheetApp.getActive();
  var sh = ss.getSheetByName('יומן סנכרון') || ss.insertSheet('יומן סנכרון');
  sh.clear();
  sh.getRange(1,1).setValue('הרצה: ' +
    Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'dd/MM/yyyy HH:mm:ss'));
  // שורה שמתחילה ב- = + - @ מתפרשת כנוסחה (#ERROR!). גרש בהתחלה מכריח טקסט.
  var out = log.map(function (l) { return [/^[=+\-@]/.test(l) ? "'" + l : l]; });
  if (out.length) sh.getRange(2,1,out.length,1).setValues(out);
  sh.setColumnWidth(1, 900);
  try {
    SpreadsheetApp.getUi().alert(log.slice(0,2).join('\n') + '\n\nפירוט מלא בגיליון "יומן סנכרון".');
  } catch (e) { /* הופעל מטריגר, אין UI */ }
}
