/* 课表识别内核：跑在教务系统页面里，把当前页的表格/文字变成课程数组。
   对外只有一个入口 window.__dshScan()，返回：
   { ok, count, source, url, title, note, courses:[{name,room,teacher,day,start,end,weeks,color}] }
   day 为 0..6（周一到周日）。 */
(function (g) {
  var DAYS = '一二三四五六日天';

  var PERIODS = [
    ['08:00', '08:45'], ['08:55', '09:40'], ['10:00', '10:45'], ['10:55', '11:40'],
    ['14:00', '14:45'], ['14:55', '15:40'], ['16:00', '16:45'], ['16:55', '17:40'],
    ['19:00', '19:45'], ['19:55', '20:40'], ['20:50', '21:35'], ['21:45', '22:30']
  ];

  function minOf(hh) {
    var p = String(hh).split(':');
    if (p.length < 2) return -1;
    return parseInt(p[0], 10) * 60 + parseInt(p[1], 10);
  }

  function mkPeriodOf(periodsFromPage) {
    var list = periodsFromPage && periodsFromPage.length ? periodsFromPage : null;
    var ps = [];
    if (list) {
      for (var i = 0; i < list.length; i++) ps.push([minOf(list[i].start), minOf(list[i].end)]);
    } else {
      for (var j = 0; j < PERIODS.length; j++) ps.push([minOf(PERIODS[j][0]), minOf(PERIODS[j][1])]);
    }
    var fn = function (mins) {
      for (var k = 0; k < ps.length; k++) {
        if (mins >= ps[k][0] && mins <= ps[k][1]) return k + 1;
      }
      return 0;
    };
    fn.count = ps.length;
    fn.starts = ps;
    return fn;
  }
  function normWeeks(base, flag) {
    var b = String(base).replace(/[\s，、]/g, ',').replace(/[~–—]/g, '-');
    var f = /单/.test(flag || '') ? ':odd' : (/双/.test(flag || '') ? ':even' : '');
    return b + f;
  }

  function pickWeeks(s, totalWeeks) {
    var S = String(s || '');
    var m = S.match(/(\d{1,2}(?:\s*[-–—~]\s*\d{1,2})?(?:\s*[,，、]\s*\d{1,2}(?:\s*[-–—~]\s*\d{1,2})?)*)\s*周\s*([(（]?[单双][)）]?)?/);
    if (m) {
      var f2 = m[2] || ((S.match(/[(（]?\s*([单双])\s*[)）]?\s*周/) || [])[1] || '');
      return normWeeks(m[1], f2);
    }
    var m2 = S.match(/(\d{1,2})\s*[-–—~]\s*(\d{1,2})\s*[(（]?\s*([单双])\s*[)）]?/);
    var mark = (S.match(/[(（]?\s*([单双])\s*[)）]?\s*周/) || [])[1] || '';
    if (!m2 && mark) {
      var r = S.match(/(\d{1,2}(?:\s*[-–—~]\s*\d{1,2})?(?:\s*[,，、]\s*\d{1,2}(?:\s*[-–—~]\s*\d{1,2})?)*)/);
      if (r) return normWeeks(r[1], mark);
    }
    if (m2) return normWeeks(m2[1] + '-' + m2[2], m2[3]);
    return '1-' + (totalWeeks || 16);
  }

  function stripWeeks(s) {
    return String(s).replace(/\d{1,2}(?:\s*[-–—~]\s*\d{1,2})?(?:\s*[,，、]\s*\d{1,2}(?:\s*[-–—~]\s*\d{1,2})?)*\s*周\s*[(（]?[单双]?[)）]?/, ' ');
  }

  function dedupe(list) {
    var seen = {}, out = [];
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      var k = c.day + '|' + c.start + '|' + c.end + '|' + c.name;
      if (seen[k]) continue;
      seen[k] = 1;
      out.push(c);
    }
    return out;
  }

  /* 按 rowspan/colspan 还原表格网格 */
  function tableRows(html) {
    var trs = html.match(/<tr[\s\S]*?<\/tr>/gi) || [];
    var pend = {}, rows = [];
    for (var ri = 0; ri < trs.length; ri++) {
      var raw = trs[ri].match(/<t[dh][^>]*>[\s\S]*?<\/t[dh]>/gi) || [];
      var used = {};
      (pend[ri] || []).forEach(function (c) { used[c] = 1; });
      var cells = [], col = 0;
      for (var ci = 0; ci < raw.length; ci++) {
        var c = raw[ci];
        var open = c.match(/^<t[dh][^>]*>/i)[0];
        var rowspan = +((open.match(/rowspan\s*=\s*"?(\d+)/i) || [])[1] || 1);
        var colspan = +((open.match(/colspan\s*=\s*"?(\d+)/i) || [])[1] || 1);
        var text = c.replace(open, '').replace(/<\/t[dh]>$/i, '')
          .replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, ' ')
          .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')
          .replace(/[ \t]+/g, ' ').trim();
        while (used[col]) col++;
        cells.push({ text: text, col: col, head: /^<th/i.test(open), rowspan: rowspan, colspan: colspan });
        for (var k = 0; k < colspan; k++) {
          used[col + k] = 1;
          for (var r = 1; r < rowspan; r++) {
            (pend[ri + r] = pend[ri + r] || []).push(col + k);
          }
        }
        col += colspan;
      }
      rows.push(cells);
    }
    return rows;
  }

  function parseOneTable(html, periodOf, totalWeeks) {
    var out = [];
    var rows = tableRows(html);
    if (!rows.length) return out;
    var colMap = {}, headIdx = -1;
    for (var k = 0; k < rows.length; k++) {
      var m = {};
      var cells0 = rows[k];
      for (var i0 = 0; i0 < cells0.length; i0++) {
        var c0 = cells0[i0];
        var d = c0.text.match(/(周|星期)([一二三四五六日天])/);
        if (d) {
          var day0 = DAYS.indexOf(d[2]);
          for (var s0 = 0; s0 < (c0.colspan || 1); s0++) m[c0.col + s0] = day0;
        }
      }
      if (Object.keys(m).length >= 2) { colMap = m; headIdx = k; break; }
    }

    function pushText(text, day, st, en) {
      var t = String(text).split('\n').map(function (x) { return x.trim(); }).filter(Boolean).join(' ');
      if (!/[\u4e00-\u9fa5A-Za-z]/.test(t)) return;
      if (/^(节次|时间|星期|备注|课程表?|上午|下午|晚上)$/.test(t)) return;
      var weeks = pickWeeks(t, totalWeeks);
      var body = stripWeeks(t)
        .replace(/[(（][^)）]*(?:单|双|周)[^)）]*[)）]/g, ' ')
        .replace(/\d{1,2}:\d{2}/g, ' ');
      var parts = body.split(/[\s,，/、]+/).filter(Boolean);
      var name = parts[0] || '';
      if (!name || /^\d+$/.test(name)) return;
      var room = '';
      for (var i = 1; i < parts.length; i++) {
        if (/楼|馆|室|教|机房|场|区|实验|中心|A\d|B\d|\d{3}/.test(parts[i])) { room = parts[i]; break; }
      }
      var teacher = '';
      for (var j = 1; j < parts.length; j++) {
        if (parts[j] !== name && parts[j] !== room && /^[\u4e00-\u9fa5]{2,4}$/.test(parts[j])) { teacher = parts[j]; break; }
      }
      out.push({ name: name, room: room, teacher: teacher, day: day, start: st, end: en, weeks: weeks });
    }

    if (headIdx < 0) {
      for (var r1 = 0; r1 < rows.length; r1++) {
        var row = rows[r1];
        var line = row.map(function (c) { return c.text; }).join(' | ');
        var dm = line.match(/(周|星期)([一二三四五六日天])/);
        if (!dm) continue;
        var dayR = DAYS.indexOf(dm[2]);
        var pm = line.match(/(\d{1,2})\s*[-–—~]\s*(\d{1,2})\s*节/);
        var st = pm ? +pm[1] : 1, en = pm ? +pm[2] : 2;
        pushText(line.replace(dm[0], ' '), dayR, st, en);
      }
      return out;
    }

    var dayCols = Object.keys(colMap).map(Number);
    var byCol = function (cells, col) {
      for (var i = 0; i < cells.length; i++) if (cells[i].col === col) return cells[i];
      return null;
    };
    var seq = 0;
    for (var r2 = headIdx + 1; r2 < rows.length; r2++) {
      var cells = rows[r2];
      if (!cells.length) continue;
      var joined = cells.map(function (c) { return c.text; }).join(' | ');
      var st2 = 0, en2 = 0;
      var pm2 = joined.match(/(\d{1,2})\s*[-–—~]\s*(\d{1,2})\s*节/);
      if (pm2) { st2 = +pm2[1]; en2 = +pm2[2]; }
      else {
        var p1 = joined.match(/第?\s*(\d{1,2})\s*节/);
        if (p1) { st2 = +p1[1]; en2 = st2; }
        else {
          for (var i3 = 0; i3 < cells.length; i3++) {
            if (dayCols.indexOf(cells[i3].col) >= 0) continue;
            if (/^\d{1,2}$/.test(cells[i3].text)) { st2 = +cells[i3].text; en2 = st2; break; }
          }
        }
      }
      if (!st2) { st2 = seq + 1; en2 = st2; }
      seq = Math.max(seq, st2);
      for (var c2 = 0; c2 < dayCols.length; c2++) {
        var col2 = dayCols[c2];
        var cell = byCol(cells, col2);
        if (!cell || !cell.text) continue;
        pushText(cell.text, colMap[col2], st2, en2);
      }
    }
    return out;
  }

  /* 制表符分隔的一行：按 token 分类，能正确处理「周一 1-2 高数 教二-105 张三 1-16」这种没有「节」「周」字样的粘贴文本 */
  function parseTabLine(line, totalWeeks, periodCount) {
    var parts = line.split('\t').map(function (x) { return x.trim(); }).filter(Boolean);
    if (parts.length < 3) return null;
    var day = -1, dm = line.match(/(周[一二三四五六日天]|星期[一二三四五六日天])/);
    if (!dm) return null;
    day = DAYS.indexOf(dm[1].slice(-1));
    if (day < 0) return null;

    var start = 0, end = 0, hasPeriod = false, weeks = '', room = '', teacher = '', name = '';
    var weekParts = [];
    for (var i = 0; i < parts.length; i++) {
      var t = parts[i];
      if (/^(周|星期)[一二三四五六日天]$/.test(t)) continue;
      if (/节/.test(t)) {
        var pm = t.match(/(\d{1,2})\s*[-–—~]\s*(\d{1,2})/) || t.match(/(\d{1,2})/);
        if (pm && !hasPeriod) {
          start = +pm[1];
          end = pm[2] ? +pm[2] : start;
          hasPeriod = true;
          continue;
        }
      }
      if (/周/.test(t)) { weekParts.push(t); continue; }
      var rm = t.match(/^(\d{1,2})\s*[-–—~]\s*(\d{1,2})\s*[,，、]\s*(\d)/);
      if (rm) { weekParts.push(t); continue; }
      var rg = t.match(/^(\d{1,2})\s*[-–—~]\s*(\d{1,2})$/) || t.match(/^(\d{1,2})$/);
      if (rg) {
        var a = +rg[1], b = rg[2] ? +rg[2] : a;
        if (!hasPeriod && b <= periodCount && a >= 1) {
          start = a; end = b; hasPeriod = true;
        } else {
          weekParts.push(t);
        }
        continue;
      }
      if (!room && /楼|馆|室|机房|场|区|实验|中心|A\d|B\d|\d{3}/.test(t)) { room = t; continue; }
      if (!name) { name = t; continue; }
      if (!teacher && /^[\u4e00-\u9fa5]{2,4}$/.test(t)) { teacher = t; continue; }
    }
    if (!name) return null;
    if (!hasPeriod) { start = 1; end = 2; }
    for (var w = 0; w < weekParts.length; w++) {
      if (/周/.test(weekParts[w])) { weeks = pickWeeks(weekParts[w], totalWeeks); break; }
    }
    if (!weeks && weekParts.length) {
      var last = weekParts[weekParts.length - 1];
      var flag = /单/.test(last) ? '单' : (/双/.test(last) ? '双' : '');
      weeks = normWeeks(last.replace(/[^\d,\-–—~]/g, ''), flag);
    }
    if (!weeks) weeks = '1-' + (totalWeeks || 16);
    return { name: name, room: room, teacher: teacher, day: day, start: start, end: end, weeks: weeks };
  }

  function parseText(txt, periodOf, totalWeeks) {
    var out = [];
    var lines = String(txt).split(/\r?\n/);
    var periodCount = (periodOf && periodOf.count) ? periodOf.count : 12;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (!line || line.length < 3) continue;
      if (line.indexOf('\t') >= 0) {
        var tc = parseTabLine(line, totalWeeks, periodCount);
        if (tc) { out.push(tc); continue; }
      }
      var dm = line.match(/(周[一二三四五六日天]|星期[一二三四五六日天])/);
      if (!dm) continue;
      var day = DAYS.indexOf(dm[1].slice(-1));
      if (day < 0) continue;
      var start = 1, end = 2;
      var pm = line.match(/第?\s*(\d+)\s*[-–—~,，]\s*(\d+)\s*节/) || line.match(/第?\s*(\d+)\s*节/);
      if (pm) {
        start = +pm[1];
        end = pm[2] ? +pm[2] : start + 1;
      } else {
        var tm = line.match(/(\d{1,2}):(\d{2})\s*[-–—~]\s*(\d{1,2}):(\d{2})/);
        if (tm) {
          var p1 = periodOf(+tm[1] * 60 + +tm[2]);
          if (p1) start = p1;
          var pEnd = periodOf(+tm[3] * 60 + +tm[4] - 1);
          end = (pEnd && pEnd >= start) ? pEnd : start;
        } else {
          var t1 = line.match(/(\d{1,2}):(\d{2})/);
          if (t1) {
            var p2 = periodOf(+t1[1] * 60 + +t1[2]);
            if (p2) { start = p2; end = p2; }
          }
        }
      }
      if (end < start) end = start;
      var weeks = pickWeeks(line, totalWeeks);
      var rest = ' ' + line + ' ';
      rest = rest.replace(dm[0], ' ').replace(pm ? pm[0] : '', ' ');
      rest = stripWeeks(rest)
        .replace(/[(（][^)）]*(?:单|双|周)[^)）]*[)）]/g, ' ')
        .replace(/\d{1,2}:\d{2}\s*[-–—~]\s*\d{1,2}:\d{2}/g, ' ')
        .replace(/\d{1,2}:\d{2}/g, ' ')
        .replace(/[\t,，;；]+/g, ' ').trim();
      var parts = rest.split(/\s+/).filter(Boolean);
      if (!parts.length) continue;
      var name = parts[0];
      if (!name || /^\d+$/.test(name) || name.length > 30) continue;
      var room = '';
      for (var k = 1; k < parts.length; k++) {
        if (/楼|馆|室|机房|场|区|实验|中心|A\d|B\d|\d{3}/.test(parts[k])) { room = parts[k]; break; }
      }
      var teacher = '';
      for (var j = 1; j < parts.length; j++) {
        if (parts[j] !== name && parts[j] !== room && /^[\u4e00-\u9fa5]{2,4}$/.test(parts[j])) { teacher = parts[j]; break; }
      }
      out.push({ name: name, room: room, teacher: teacher, day: day, start: start, end: end, weeks: weeks });
    }
    return out;
  }

  /* 从页面里收集候选内容：所有表格 + 正文文本（含同源 iframe） */
  function collect() {
    var tables = [], text = '';
    function harvest(doc) {
      if (!doc) return;
      try {
        var ts = doc.getElementsByTagName('table');
        for (var i = 0; i < ts.length && tables.length < 40; i++) {
          tables.push(ts[i].outerHTML || '');
        }
        var body = doc.body;
        if (body) {
          var t = (body.innerText || body.textContent || '');
          if (t) text += t + '\n';
        }
        var fs = doc.getElementsByTagName('iframe');
        for (var j = 0; j < fs.length && j < 12; j++) {
          try {
            var d = fs[j].contentDocument;
            if (d) harvest(d);
          } catch (e) { /* 跨域 iframe 跳过 */ }
        }
      } catch (e2) { /* ignore */ }
    }
    harvest(g.document);
    return { tables: tables, text: text.slice(0, 60000) };
  }

  /* 稻星/正方系「个人课表」：table#kbxx
     - thead 里 th 内含 .week-info（周一..周日）与 .date-info（11-16）
     - 第一列 td 含 .td-course-time（.course-num 是节次）
     - 课程格 rowspan=2（连上两节），冲突格里有多个 .mycourse，另带 .ctdiv「冲突」
     - 合并格下方还有一份 display:none 的占位格子，必须跳过，否则整行会右移一列 */
  function cleanText(s) {
    return String(s).replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
  }

  function hhmm(s) {
  var m = String(s || '').match(/(\d{1,2})\s*[:：]\s*(\d{1,2})/);
  if (!m) return '';
  return ('0' + m[1]).slice(-2) + ':' + ('0' + m[2]).slice(-2);
}

function parseKbxx(html, totalWeeks) {
    var out = [];
    out.periods = [];
    var tw = totalWeeks || 16;
    if (!/td-course-time|course-name/.test(html)) return out;
    var days = [];
    var hm = html.match(/<thead[\s\S]*?<\/thead>/i);
    var ths = (hm ? hm[0] : '').match(/<th[^>]*>[\s\S]*?<\/th>/gi) || [];
    if (ths.length >= 3) {
      for (var i = 1; i < ths.length; i++) {
        var m0 = cleanText(ths[i]).match(/(?:周|星期)([一二三四五六日天])/);
        if (m0) days.push(DAYS.indexOf(m0[1]));
      }
    }
    if (days.length < 5) days = [0, 1, 2, 3, 4, 5, 6];

    var bm = html.match(/<tbody[^>]*id="kbInfo"[\s\S]*?<\/tbody>/i) || html.match(/<tbody[\s\S]*?<\/tbody>/i);
    if (!bm) return out;
    var trs = bm[0].match(/<tr[\s\S]*?<\/tr>/gi) || [];
    var pend = {};
    for (var ri = 0; ri < trs.length; ri++) {
      var used = {};
      (pend[ri] || []).forEach(function (c) { used[c] = 1; });
      var tds = trs[ri].match(/<td[^>]*>[\s\S]*?<\/td>/gi) || [];
      var col = 0, rowPeriod = 0;
      for (var ci = 0; ci < tds.length; ci++) {
        var td = tds[ci];
        var open = td.match(/^<td[^>]*>/i)[0];
        if (/display\s*:\s*none/i.test(open)) continue;      // 隐藏占位格，不占列
        var rowspan = +((open.match(/rowspan\s*=\s*"?(\d+)/i) || [])[1] || 1);
        var colspan = +((open.match(/colspan\s*=\s*"?(\d+)/i) || [])[1] || 1);
        while (used[col]) col++;
        var c0 = col;
        for (var k = 0; k < colspan; k++) {
          used[c0 + k] = 1;
          for (var r = 1; r < rowspan; r++) (pend[ri + r] = pend[ri + r] || []).push(c0 + k);
        }
        col = c0 + colspan;

        if (/td-course-time/.test(td)) {
          var pn = (td.match(/class="course-num"[^>]*>\s*(\d{1,2})/i) || [])[1];
          if (pn) {
            rowPeriod = +pn;
            var ts = (td.match(/class="time-start"[^>]*>\s*([\d:：]+)/i) || [])[1] || '';
            var te = (td.match(/class="time-end"[^>]*>\s*([\d:：]+)/i) || [])[1] || '';
            if (ts && te) out.periods.push({ no: rowPeriod, start: hhmm(ts), end: hhmm(te) });
          }
          continue;
        }
        if (!rowPeriod) continue;
        var day = days[c0 - 1];
        if (day === undefined || day < 0 || day > 6) continue;
        var st = rowPeriod;
        var en = rowPeriod + rowspan - 1;
        var blocks = td.split(/class="mycourse"/i);
        var items = blocks.length > 1 ? blocks.slice(1) : [td];
        for (var q = 0; q < items.length; q++) {
          var blk = items[q];
          var nm = (blk.match(/class="course-name"[^>]*>([\s\S]*?)</i) || [])[1];
          if (!nm) continue;
          var name = cleanText(nm);
          if (!name) continue;
          var te = (blk.match(/class="teacher-info"[^>]*>([\s\S]*?)</i) || [])[1] || '';
          var pl = (blk.match(/class="place-info"[^>]*>([\s\S]*?)</i) || [])[1] || '';
          out.push({
            name: name,
            room: cleanText(pl),
            teacher: cleanText(te),
            day: day,
            start: st,
            end: en,
            weeks: pickWeeks(cleanText(blk), tw) || ('1-' + tw)
          });
        }
      }
    }
    return out;
  }

  /* 从页面头部的「11周 + 周一 11-16」反推学期第一周的周一，这样周次能跟学校对齐 */
  function kbTermStart(html) {
    var wm = html.match(/>\s*(\d{1,2})\s*周\s*</);
    var dm = html.match(/class="date-info"[^>]*>\s*(\d{1,2})\s*-\s*(\d{1,2})/);
    if (!wm || !dm) return '';
    var wk = +wm[1], mo = +dm[1], dy = +dm[2];
    if (!wk || wk > 30) return '';
    /* 年份不能直接取「今年」：期末周（第 19 周）的表头日期是次年 1 月，
       直接取今年会算成上个学年。改成本年度前后一年里，离今天最近的那一天。 */
    var ty = new Date().getFullYear(), bestT = null, bestD = null;
    for (var k = -1; k <= 1; k++) {
      var c = new Date(ty + k, mo - 1, dy);
      if (c.getMonth() !== mo - 1) continue;
      var diff = Math.abs(c.getTime() - Date.now());
      if (bestD === null || diff < bestD) { bestD = diff; bestT = c.getTime(); }
    }
    if (bestT === null) return '';
    var d = new Date(bestT);
    if (d.getMonth() !== mo - 1) return '';
    d.setDate(d.getDate() - (wk - 1) * 7);
    var mm = d.getMonth() + 1, dd = d.getDate();
    return d.getFullYear() + '-' + (mm < 10 ? '0' + mm : mm) + '-' + (dd < 10 ? '0' + dd : dd);
  }
  /* 同一门课被教务拆成相邻两格（各占一节）时合并成一门连堂课 */
  function mergeAdjacent(list) {
    var out = [], used = {};
    for (var i = 0; i < list.length; i++) {
      if (used[i]) continue;
      var a = list[i];
      for (var j = i + 1; j < list.length; j++) {
        if (used[j]) continue;
        var b = list[j];
        if (a.day !== b.day || a.name !== b.name) continue;
        if ((a.room || '') !== (b.room || '')) continue;
        if ((a.teacher || '') !== (b.teacher || '')) continue;
        if ((a.weeks || '') !== (b.weeks || '')) continue;
        if (b.start <= a.end + 1 && b.end >= a.start - 1) {
          a.start = Math.min(a.start, b.start);
          a.end = Math.max(a.end, b.end);
          used[j] = 1;
        }
      }
      out.push(a);
    }
    return out;
  }
  function scan(payload, periodsFromPage, totalWeeks) {
    var periodOf = mkPeriodOf(periodsFromPage);
    var tw = totalWeeks || 16;
    var data = payload && (payload.tables || payload.text) ? payload : collect();
    var best = [], bestSource = '', termStart = '', kbPeriods = [];
    var tables = data.tables || [];
    // ① 先认「稻星/正方个人课表」结构（最准）：这种表格认得出来就不再看别的表
    for (var i = 0; i < tables.length; i++) {
      if (!/kbInfo|personal-course-table/.test(tables[i])) continue;
      var kb = [], kbRaw = null;
      try { kbRaw = parseKbxx(tables[i], tw); kb = dedupe(kbRaw); } catch (e0) { kb = []; kbRaw = null; }
      if (kb.length > best.length) {
        best = kb;
        bestSource = 'kb#' + i;
        termStart = kbTermStart(tables[i]);
        kbPeriods = (kbRaw && kbRaw.periods) ? kbRaw.periods : [];
      }
    }
    // ② 认不出再退回通用表格解析
    if (best.length < 3) {
      best = [];
      bestSource = '';
      for (var i2 = 0; i2 < tables.length; i2++) {
        var got = [];
        try { got = parseOneTable(tables[i2], periodOf, tw); } catch (e) { got = []; }
        got = dedupe(got);
        if (got.length > best.length) { best = got; bestSource = 'table#' + i2; }
      }
    }
    if (data.text && (best.length === 0 || best.length < 3)) {
      var fromText = dedupe(parseText(data.text, periodOf, tw));
      if (fromText.length > best.length) { best = fromText; bestSource = 'text'; }
    }
    best = mergeAdjacent(best);
    var courses = [];
    for (var k = 0; k < best.length; k++) {
      var c = best[k];
      courses.push({
        name: c.name,
        room: c.room || '',
        teacher: c.teacher || '',
        day: c.day,
        start: c.start,
        end: c.end,
        weeks: c.weeks,
        color: k % 8
      });
    }
    var url = '', title = '';
    try { url = g.location ? g.location.href : ''; } catch (e3) { }
    try { title = g.document ? g.document.title : ''; } catch (e4) { }
    return {
      ok: courses.length > 0,
      count: courses.length,
      source: bestSource,
      tables: tables.length,
      url: url,
      title: title,
      termStart: termStart,
      periods: kbPeriods,
      courses: courses
    };
  }

  // ---------- 全部周次：把 1..N 周都取回来，合并出每门课真实的周次 ----------
  function weeksSpec(list, totalWeeks) {
    var n = totalWeeks || 16;
    if (!list || !list.length) return '1-' + n;
    if (list.length === n) return '1-' + n;
    var odd = [], even = [];
    for (var w = 1; w <= n; w++) { if (w % 2) odd.push(w); else even.push(w); }
    var key = list.join(',');
    if (key === odd.join(',')) return '1-' + n + ':odd';
    if (key === even.join(',')) return '1-' + n + ':even';
    var parts = [], st = list[0], pv = list[0];
    for (var i = 1; i < list.length; i++) {
      if (list[i] === pv + 1) { pv = list[i]; continue; }
      parts.push(st === pv ? String(st) : st + '-' + pv);
      st = pv = list[i];
    }
    parts.push(st === pv ? String(st) : st + '-' + pv);
    return parts.join(',');
  }

  // 按「同一天 / 同一门课 / 同一教室」把跨周的碎片合并成一门课，
  // 相邻节次（3 和 4）在周次完全相同时也并成 3-4，周次取并集。
  function normField(x) {
    return String(x == null ? '' : x).replace(/[\s\u3000]+/g, ' ').replace(/^[ ,，]+|[ ,，]+$/g, '').trim();
  }

  function kbUnionWeeks(weekly, totalWeeks) {
    var tw = totalWeeks || 16;
    var groups = {}, order = [];
    (weekly || []).forEach(function (pair) {
      var w = pair.w, list = pair.list || [];
      list.forEach(function (c) {
        var nm = normField(c.name), rm = normField(c.room), tc = normField(c.teacher);
        if (!nm) return;
        var gkey = [c.day, nm, rm, tc].join('|');
        if (!groups[gkey]) { groups[gkey] = []; order.push(gkey); }
        groups[gkey].push({ day: c.day, start: c.start, end: c.end, name: nm, room: rm, teacher: tc, color: c.color, w: w });
      });
    });
    var acc = {}, aorder = [];
    function wsKeyOf(o) { var ks = []; for (var k1 in o) ks.push(k1); return ks.sort(function (x, y) { return x - y; }).join(','); }
    order.forEach(function (gkey) {
      var items = groups[gkey].slice().sort(function (a, b) { return a.start - b.start; });
      // 必须按「同一周」分别合并相邻节次：不同周的同一门课会交错排列，
      // 混在一起比较会让 last 落在别的周上，永远并不到一起。
      var byWeek = {};
      items.forEach(function (it) { (byWeek[it.w] = byWeek[it.w] || []).push(it); });
      Object.keys(byWeek).forEach(function (wk) {
        var list = byWeek[wk], runs = [];
        list.forEach(function (it) {
          var last = runs.length ? runs[runs.length - 1] : null;
          if (last && it.start <= last.end + 1) {
            if (it.end > last.end) last.end = it.end;
          } else {
            runs.push({ day: it.day, start: it.start, end: it.end, name: it.name, room: it.room, teacher: it.teacher, color: it.color });
          }
        });
        runs.forEach(function (m) {
          var ak = [m.day, m.start, m.end, m.name, m.room].join('|');
          if (!acc[ak]) {
            acc[ak] = { day: m.day, start: m.start, end: m.end, name: m.name, room: m.room, teacher: m.teacher, color: m.color, ws: {} };
            aorder.push(ak);
          }
          acc[ak].ws[wk] = 1;
        });
      });
    });
    var out = [];
    var rows = tableRows(html);
    if (!rows.length) return out;
    var colMap = {}, headIdx = -1;
    for (var k = 0; k < rows.length; k++) {
      var m = {};
      var cells0 = rows[k];
      for (var i0 = 0; i0 < cells0.length; i0++) {
        var c0 = cells0[i0];
        var d = c0.text.match(/(周|星期)([一二三四五六日天])/);
        if (d) {
          var day0 = DAYS.indexOf(d[2]);
          for (var s0 = 0; s0 < (c0.colspan || 1); s0++) m[c0.col + s0] = day0;
        }
      }
      if (Object.keys(m).length >= 2) { colMap = m; headIdx = k; break; }
    }

    function pushText(text, day, st, en) {
      var t = String(text).split('\n').map(function (x) { return x.trim(); }).filter(Boolean).join(' ');
      if (!/[\u4e00-\u9fa5A-Za-z]/.test(t)) return;
      if (/^(节次|时间|星期|备注|课程表?|上午|下午|晚上)$/.test(t)) return;
      var weeks = pickWeeks(t, totalWeeks);
      var body = stripWeeks(t)
        .replace(/[(（][^)）]*(?:单|双|周)[^)）]*[)）]/g, ' ')
        .replace(/\d{1,2}:\d{2}/g, ' ');
      var parts = body.split(/[\s,，/、]+/).filter(Boolean);
      var name = parts[0] || '';
      if (!name || /^\d+$/.test(name)) return;
      var room = '';
      for (var i = 1; i < parts.length; i++) {
        if (/楼|馆|室|教|机房|场|区|实验|中心|A\d|B\d|\d{3}/.test(parts[i])) { room = parts[i]; break; }
      }
      var teacher = '';
      for (var j = 1; j < parts.length; j++) {
        if (parts[j] !== name && parts[j] !== room && /^[\u4e00-\u9fa5]{2,4}$/.test(parts[j])) { teacher = parts[j]; break; }
      }
      out.push({ name: name, room: room, teacher: teacher, day: day, start: st, end: en, weeks: weeks });
    }

    if (headIdx < 0) {
      for (var r1 = 0; r1 < rows.length; r1++) {
        var row = rows[r1];
        var line = row.map(function (c) { return c.text; }).join(' | ');
        var dm = line.match(/(周|星期)([一二三四五六日天])/);
        if (!dm) continue;
        var dayR = DAYS.indexOf(dm[2]);
        var pm = line.match(/(\d{1,2})\s*[-–—~]\s*(\d{1,2})\s*节/);
        var st = pm ? +pm[1] : 1, en = pm ? +pm[2] : 2;
        pushText(line.replace(dm[0], ' '), dayR, st, en);
      }
      return out;
    }

    var dayCols = Object.keys(colMap).map(Number);
    var byCol = function (cells, col) {
      for (var i = 0; i < cells.length; i++) if (cells[i].col === col) return cells[i];
      return null;
    };
    var seq = 0;
    for (var r2 = headIdx + 1; r2 < rows.length; r2++) {
      var cells = rows[r2];
      if (!cells.length) continue;
      var joined = cells.map(function (c) { return c.text; }).join(' | ');
      var st2 = 0, en2 = 0;
      var pm2 = joined.match(/(\d{1,2})\s*[-–—~]\s*(\d{1,2})\s*节/);
      if (pm2) { st2 = +pm2[1]; en2 = +pm2[2]; }
      else {
        var p1 = joined.match(/第?\s*(\d{1,2})\s*节/);
        if (p1) { st2 = +p1[1]; en2 = st2; }
        else {
          for (var i3 = 0; i3 < cells.length; i3++) {
            if (dayCols.indexOf(cells[i3].col) >= 0) continue;
            if (/^\d{1,2}$/.test(cells[i3].text)) { st2 = +cells[i3].text; en2 = st2; break; }
          }
        }
      }
      if (!st2) { st2 = seq + 1; en2 = st2; }
      seq = Math.max(seq, st2);
      for (var c2 = 0; c2 < dayCols.length; c2++) {
        var col2 = dayCols[c2];
        var cell = byCol(cells, col2);
        if (!cell || !cell.text) continue;
        pushText(cell.text, colMap[col2], st2, en2);
      }
    }
    return out;
  }

  /* 制表符分隔的一行：按 token 分类，能正确处理「周一 1-2 高数 教二-105 张三 1-16」这种没有「节」「周」字样的粘贴文本 */
  function parseTabLine(line, totalWeeks, periodCount) {
    var parts = line.split('\t').map(function (x) { return x.trim(); }).filter(Boolean);
    if (parts.length < 3) return null;
    var day = -1, dm = line.match(/(周[一二三四五六日天]|星期[一二三四五六日天])/);
    if (!dm) return null;
    day = DAYS.indexOf(dm[1].slice(-1));
    if (day < 0) return null;

    var start = 0, end = 0, hasPeriod = false, weeks = '', room = '', teacher = '', name = '';
    var weekParts = [];
    for (var i = 0; i < parts.length; i++) {
      var t = parts[i];
      if (/^(周|星期)[一二三四五六日天]$/.test(t)) continue;
      if (/节/.test(t)) {
        var pm = t.match(/(\d{1,2})\s*[-–—~]\s*(\d{1,2})/) || t.match(/(\d{1,2})/);
        if (pm && !hasPeriod) {
          start = +pm[1];
          end = pm[2] ? +pm[2] : start;
          hasPeriod = true;
          continue;
        }
      }
      if (/周/.test(t)) { weekParts.push(t); continue; }
      var rm = t.match(/^(\d{1,2})\s*[-–—~]\s*(\d{1,2})\s*[,，、]\s*(\d)/);
      if (rm) { weekParts.push(t); continue; }
      var rg = t.match(/^(\d{1,2})\s*[-–—~]\s*(\d{1,2})$/) || t.match(/^(\d{1,2})$/);
      if (rg) {
        var a = +rg[1], b = rg[2] ? +rg[2] : a;
        if (!hasPeriod && b <= periodCount && a >= 1) {
          start = a; end = b; hasPeriod = true;
        } else {
          weekParts.push(t);
        }
        continue;
      }
      if (!room && /楼|馆|室|机房|场|区|实验|中心|A\d|B\d|\d{3}/.test(t)) { room = t; continue; }
      if (!name) { name = t; continue; }
      if (!teacher && /^[\u4e00-\u9fa5]{2,4}$/.test(t)) { teacher = t; continue; }
    }
    if (!name) return null;
    if (!hasPeriod) { start = 1; end = 2; }
    for (var w = 0; w < weekParts.length; w++) {
      if (/周/.test(weekParts[w])) { weeks = pickWeeks(weekParts[w], totalWeeks); break; }
    }
    if (!weeks && weekParts.length) {
      var last = weekParts[weekParts.length - 1];
      var flag = /单/.test(last) ? '单' : (/双/.test(last) ? '双' : '');
      weeks = normWeeks(last.replace(/[^\d,\-–—~]/g, ''), flag);
    }
    if (!weeks) weeks = '1-' + (totalWeeks || 16);
    return { name: name, room: room, teacher: teacher, day: day, start: start, end: end, weeks: weeks };
  }

  function parseText(txt, periodOf, totalWeeks) {
    var out = [];
    var lines = String(txt).split(/\r?\n/);
    var periodCount = (periodOf && periodOf.count) ? periodOf.count : 12;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (!line || line.length < 3) continue;
      if (line.indexOf('\t') >= 0) {
        var tc = parseTabLine(line, totalWeeks, periodCount);
        if (tc) { out.push(tc); continue; }
      }
      var dm = line.match(/(周[一二三四五六日天]|星期[一二三四五六日天])/);
      if (!dm) continue;
      var day = DAYS.indexOf(dm[1].slice(-1));
      if (day < 0) continue;
      var start = 1, end = 2;
      var pm = line.match(/第?\s*(\d+)\s*[-–—~,，]\s*(\d+)\s*节/) || line.match(/第?\s*(\d+)\s*节/);
      if (pm) {
        start = +pm[1];
        end = pm[2] ? +pm[2] : start + 1;
      } else {
        var tm = line.match(/(\d{1,2}):(\d{2})\s*[-–—~]\s*(\d{1,2}):(\d{2})/);
        if (tm) {
          var p1 = periodOf(+tm[1] * 60 + +tm[2]);
          if (p1) start = p1;
          var pEnd = periodOf(+tm[3] * 60 + +tm[4] - 1);
          end = (pEnd && pEnd >= start) ? pEnd : start;
        } else {
          var t1 = line.match(/(\d{1,2}):(\d{2})/);
          if (t1) {
            var p2 = periodOf(+t1[1] * 60 + +t1[2]);
            if (p2) { start = p2; end = p2; }
          }
        }
      }
      if (end < start) end = start;
      var weeks = pickWeeks(line, totalWeeks);
      var rest = ' ' + line + ' ';
      rest = rest.replace(dm[0], ' ').replace(pm ? pm[0] : '', ' ');
      rest = stripWeeks(rest)
        .replace(/[(（][^)）]*(?:单|双|周)[^)）]*[)）]/g, ' ')
        .replace(/\d{1,2}:\d{2}\s*[-–—~]\s*\d{1,2}:\d{2}/g, ' ')
        .replace(/\d{1,2}:\d{2}/g, ' ')
        .replace(/[\t,，;；]+/g, ' ').trim();
      var parts = rest.split(/\s+/).filter(Boolean);
      if (!parts.length) continue;
      var name = parts[0];
      if (!name || /^\d+$/.test(name) || name.length > 30) continue;
      var room = '';
      for (var k = 1; k < parts.length; k++) {
        if (/楼|馆|室|机房|场|区|实验|中心|A\d|B\d|\d{3}/.test(parts[k])) { room = parts[k]; break; }
      }
      var teacher = '';
      for (var j = 1; j < parts.length; j++) {
        if (parts[j] !== name && parts[j] !== room && /^[\u4e00-\u9fa5]{2,4}$/.test(parts[j])) { teacher = parts[j]; break; }
      }
      out.push({ name: name, room: room, teacher: teacher, day: day, start: start, end: end, weeks: weeks });
    }
    return out;
  }

  /* 从页面里收集候选内容：所有表格 + 正文文本（含同源 iframe） */
  function collect() {
    var tables = [], text = '';
    function harvest(doc) {
      if (!doc) return;
      try {
        var ts = doc.getElementsByTagName('table');
        for (var i = 0; i < ts.length && tables.length < 40; i++) {
          tables.push(ts[i].outerHTML || '');
        }
        var body = doc.body;
        if (body) {
          var t = (body.innerText || body.textContent || '');
          if (t) text += t + '\n';
        }
        var fs = doc.getElementsByTagName('iframe');
        for (var j = 0; j < fs.length && j < 12; j++) {
          try {
            var d = fs[j].contentDocument;
            if (d) harvest(d);
          } catch (e) { /* 跨域 iframe 跳过 */ }
        }
      } catch (e2) { /* ignore */ }
    }
    harvest(g.document);
    return { tables: tables, text: text.slice(0, 60000) };
  }

  /* 稻星/正方系「个人课表」：table#kbxx
     - thead 里 th 内含 .week-info（周一..周日）与 .date-info（11-16）
     - 第一列 td 含 .td-course-time（.course-num 是节次）
     - 课程格 rowspan=2（连上两节），冲突格里有多个 .mycourse，另带 .ctdiv「冲突」
     - 合并格下方还有一份 display:none 的占位格子，必须跳过，否则整行会右移一列 */
  function cleanText(s) {
    return String(s).replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
  }

  function hhmm(s) {
  var m = String(s || '').match(/(\d{1,2})\s*[:：]\s*(\d{1,2})/);
  if (!m) return '';
  return ('0' + m[1]).slice(-2) + ':' + ('0' + m[2]).slice(-2);
}

function parseKbxx(html, totalWeeks) {
    var out = [];
    out.periods = [];
    var tw = totalWeeks || 16;
    if (!/td-course-time|course-name/.test(html)) return out;
    var days = [];
    var hm = html.match(/<thead[\s\S]*?<\/thead>/i);
    var ths = (hm ? hm[0] : '').match(/<th[^>]*>[\s\S]*?<\/th>/gi) || [];
    if (ths.length >= 3) {
      for (var i = 1; i < ths.length; i++) {
        var m0 = cleanText(ths[i]).match(/(?:周|星期)([一二三四五六日天])/);
        if (m0) days.push(DAYS.indexOf(m0[1]));
      }
    }
    if (days.length < 5) days = [0, 1, 2, 3, 4, 5, 6];

    var bm = html.match(/<tbody[^>]*id="kbInfo"[\s\S]*?<\/tbody>/i) || html.match(/<tbody[\s\S]*?<\/tbody>/i);
    if (!bm) return out;
    var trs = bm[0].match(/<tr[\s\S]*?<\/tr>/gi) || [];
    var pend = {};
    for (var ri = 0; ri < trs.length; ri++) {
      var used = {};
      (pend[ri] || []).forEach(function (c) { used[c] = 1; });
      var tds = trs[ri].match(/<td[^>]*>[\s\S]*?<\/td>/gi) || [];
      var col = 0, rowPeriod = 0;
      for (var ci = 0; ci < tds.length; ci++) {
        var td = tds[ci];
        var open = td.match(/^<td[^>]*>/i)[0];
        if (/display\s*:\s*none/i.test(open)) continue;      // 隐藏占位格，不占列
        var rowspan = +((open.match(/rowspan\s*=\s*"?(\d+)/i) || [])[1] || 1);
        var colspan = +((open.match(/colspan\s*=\s*"?(\d+)/i) || [])[1] || 1);
        while (used[col]) col++;
        var c0 = col;
        for (var k = 0; k < colspan; k++) {
          used[c0 + k] = 1;
          for (var r = 1; r < rowspan; r++) (pend[ri + r] = pend[ri + r] || []).push(c0 + k);
        }
        col = c0 + colspan;

        if (/td-course-time/.test(td)) {
          var pn = (td.match(/class="course-num"[^>]*>\s*(\d{1,2})/i) || [])[1];
          if (pn) {
            rowPeriod = +pn;
            var ts = (td.match(/class="time-start"[^>]*>\s*([\d:：]+)/i) || [])[1] || '';
            var te = (td.match(/class="time-end"[^>]*>\s*([\d:：]+)/i) || [])[1] || '';
            if (ts && te) out.periods.push({ no: rowPeriod, start: hhmm(ts), end: hhmm(te) });
          }
          continue;
        }
        if (!rowPeriod) continue;
        var day = days[c0 - 1];
        if (day === undefined || day < 0 || day > 6) continue;
        var st = rowPeriod;
        var en = rowPeriod + rowspan - 1;
        var blocks = td.split(/class="mycourse"/i);
        var items = blocks.length > 1 ? blocks.slice(1) : [td];
        for (var q = 0; q < items.length; q++) {
          var blk = items[q];
          var nm = (blk.match(/class="course-name"[^>]*>([\s\S]*?)</i) || [])[1];
          if (!nm) continue;
          var name = cleanText(nm);
          if (!name) continue;
          var te = (blk.match(/class="teacher-info"[^>]*>([\s\S]*?)</i) || [])[1] || '';
          var pl = (blk.match(/class="place-info"[^>]*>([\s\S]*?)</i) || [])[1] || '';
          out.push({
            name: name,
            room: cleanText(pl),
            teacher: cleanText(te),
            day: day,
            start: st,
            end: en,
            weeks: pickWeeks(cleanText(blk), tw) || ('1-' + tw)
          });
        }
      }
    }
    return out;
  }

  /* 从页面头部的「11周 + 周一 11-16」反推学期第一周的周一，这样周次能跟学校对齐 */
  function kbTermStart(html) {
    var wm = html.match(/>\s*(\d{1,2})\s*周\s*</);
    var dm = html.match(/class="date-info"[^>]*>\s*(\d{1,2})\s*-\s*(\d{1,2})/);
    if (!wm || !dm) return '';
    var wk = +wm[1], mo = +dm[1], dy = +dm[2];
    if (!wk || wk > 30) return '';
    /* 年份不能直接取「今年」：期末周的表头日期可能是次年 1 月，
       取今年会算成上个学年。改成前后一年里离今天最近的那一天。 */
    var ty = new Date().getFullYear(), bestT = null, bestD = null;
    for (var k = -1; k <= 1; k++) {
      var c = new Date(ty + k, mo - 1, dy);
      if (c.getMonth() !== mo - 1) continue;
      var diff = Math.abs(c.getTime() - Date.now());
      if (bestD === null || diff < bestD) { bestD = diff; bestT = c.getTime(); }
    }
    if (bestT === null) return '';
    var d = new Date(bestT);
    if (d.getMonth() !== mo - 1) return '';
    d.setDate(d.getDate() - (wk - 1) * 7);
    var mm = d.getMonth() + 1, dd = d.getDate();
    return d.getFullYear() + '-' + (mm < 10 ? '0' + mm : mm) + '-' + (dd < 10 ? '0' + dd : dd);
  }
  /* 同一门课被教务拆成相邻两格（各占一节）时合并成一门连堂课 */
  function mergeAdjacent(list) {
    var out = [], used = {};
    for (var i = 0; i < list.length; i++) {
      if (used[i]) continue;
      var a = list[i];
      for (var j = i + 1; j < list.length; j++) {
        if (used[j]) continue;
        var b = list[j];
        if (a.day !== b.day || a.name !== b.name) continue;
        if ((a.room || '') !== (b.room || '')) continue;
        if ((a.teacher || '') !== (b.teacher || '')) continue;
        if ((a.weeks || '') !== (b.weeks || '')) continue;
        if (b.start <= a.end + 1 && b.end >= a.start - 1) {
          a.start = Math.min(a.start, b.start);
          a.end = Math.max(a.end, b.end);
          used[j] = 1;
        }
      }
      out.push(a);
    }
    return out;
  }
  function scan(payload, periodsFromPage, totalWeeks) {
    var periodOf = mkPeriodOf(periodsFromPage);
    var tw = totalWeeks || 16;
    var data = payload && (payload.tables || payload.text) ? payload : collect();
    var best = [], bestSource = '', termStart = '', kbPeriods = [];
    var tables = data.tables || [];
    // ① 先认「稻星/正方个人课表」结构（最准）：这种表格认得出来就不再看别的表
    for (var i = 0; i < tables.length; i++) {
      if (!/kbInfo|personal-course-table/.test(tables[i])) continue;
      var kb = [], kbRaw = null;
      try { kbRaw = parseKbxx(tables[i], tw); kb = dedupe(kbRaw); } catch (e0) { kb = []; kbRaw = null; }
      if (kb.length > best.length) {
        best = kb;
        bestSource = 'kb#' + i;
        termStart = kbTermStart(tables[i]);
        kbPeriods = (kbRaw && kbRaw.periods) ? kbRaw.periods : [];
      }
    }
    // ② 认不出再退回通用表格解析
    if (best.length < 3) {
      best = [];
      bestSource = '';
      for (var i2 = 0; i2 < tables.length; i2++) {
        var got = [];
        try { got = parseOneTable(tables[i2], periodOf, tw); } catch (e) { got = []; }
        got = dedupe(got);
        if (got.length > best.length) { best = got; bestSource = 'table#' + i2; }
      }
    }
    if (data.text && (best.length === 0 || best.length < 3)) {
      var fromText = dedupe(parseText(data.text, periodOf, tw));
      if (fromText.length > best.length) { best = fromText; bestSource = 'text'; }
    }
    best = mergeAdjacent(best);
    var courses = [];
    for (var k = 0; k < best.length; k++) {
      var c = best[k];
      courses.push({
        name: c.name,
        room: c.room || '',
        teacher: c.teacher || '',
        day: c.day,
        start: c.start,
        end: c.end,
        weeks: c.weeks,
        color: k % 8
      });
    }
    var url = '', title = '';
    try { url = g.location ? g.location.href : ''; } catch (e3) { }
    try { title = g.document ? g.document.title : ''; } catch (e4) { }
    return {
      ok: courses.length > 0,
      count: courses.length,
      source: bestSource,
      tables: tables.length,
      url: url,
      title: title,
      termStart: termStart,
      periods: kbPeriods,
      courses: courses
    };
  }

  // ---------- 全部周次：把 1..N 周都取回来，合并出每门课真实的周次 ----------
  function weeksSpec(list, totalWeeks) {
    var n = totalWeeks || 16;
    if (!list || !list.length) return '1-' + n;
    if (list.length === n) return '1-' + n;
    var odd = [], even = [];
    for (var w = 1; w <= n; w++) { if (w % 2) odd.push(w); else even.push(w); }
    var key = list.join(',');
    if (key === odd.join(',')) return '1-' + n + ':odd';
    if (key === even.join(',')) return '1-' + n + ':even';
    var parts = [], st = list[0], pv = list[0];
    for (var i = 1; i < list.length; i++) {
      if (list[i] === pv + 1) { pv = list[i]; continue; }
      parts.push(st === pv ? String(st) : st + '-' + pv);
      st = pv = list[i];
    }
    parts.push(st === pv ? String(st) : st + '-' + pv);
    return parts.join(',');
  }

  // 按「同一天 / 同一门课 / 同一教室」把跨周的碎片合并成一门课，
  // 相邻节次（3 和 4）在周次完全相同时也并成 3-4，周次取并集。
  function normField(x) {
    return String(x == null ? '' : x).replace(/[\s\u3000]+/g, ' ').replace(/^[ ,，]+|[ ,，]+$/g, '').trim();
  }

  function kbUnionWeeks(weekly, totalWeeks) {
    var tw = totalWeeks || 16;
    var groups = {}, order = [];
    (weekly || []).forEach(function (pair) {
      var w = pair.w, list = pair.list || [];
      list.forEach(function (c) {
        var nm = normField(c.name), rm = normField(c.room), tc = normField(c.teacher);
        if (!nm) return;
        var gkey = [c.day, nm, rm, tc].join('|');
        if (!groups[gkey]) { groups[gkey] = []; order.push(gkey); }
        groups[gkey].push({ day: c.day, start: c.start, end: c.end, name: nm, room: rm, teacher: tc, color: c.color, w: w });
      });
    });
    var acc = {}, aorder = [];
    function wsKeyOf(o) { var ks = []; for (var k1 in o) ks.push(k1); return ks.sort(function (x, y) { return x - y; }).join(','); }
    order.forEach(function (gkey) {
      var items = groups[gkey].slice().sort(function (a, b) { return a.start - b.start; });
      // 必须按「同一周」分别合并相邻节次：不同周的同一门课交错排列，
      // 混在一起比较会让 last 落在别的周上，永远并不到一起。
      var byWeek = {};
      items.forEach(function (it) { (byWeek[it.w] = byWeek[it.w] || []).push(it); });
      Object.keys(byWeek).forEach(function (wk) {
        var list = byWeek[wk], runs = [];
        list.forEach(function (it) {
          var last = runs.length ? runs[runs.length - 1] : null;
          if (last && it.start <= last.end + 1) {
            if (it.end > last.end) last.end = it.end;
          } else {
            runs.push({ day: it.day, start: it.start, end: it.end, name: it.name, room: it.room, teacher: it.teacher, color: it.color });
          }
        });
        runs.forEach(function (m) {
          var ak = [m.day, m.start, m.end, m.name, m.room].join('|');
          if (!acc[ak]) {
            acc[ak] = { day: m.day, start: m.start, end: m.end, name: m.name, room: m.room, teacher: m.teacher, color: m.color, ws: {} };
            aorder.push(ak);
          }
          acc[ak].ws[wk] = 1;
        });
      });
    });
    var out = [];
    aorder.forEach(function (ak) {
      var m = acc[ak], all = [];
      for (var w = 1; w <= tw; w++) { if (m.ws[w]) all.push(w); }
      if (!all.length) return;
      out.push({ day: m.day, start: m.start, end: m.end, name: m.name, room: m.room, teacher: m.teacher, color: m.color, weeks: weeksSpec(all, tw) });
    });
    out.sort(function (a, b) { return (a.day - b.day) || (a.start - b.start) || (a.name < b.name ? -1 : 1); });
    return out;
  }

  function liveBody() {
    var tb = document.getElementById('kbInfo');
    if (!tb) return '';
    var h = tb.innerHTML || '';
    return /course-name/.test(h) ? h : '';
  }

  function liveHead() {
    var th = document.getElementById('weekTr');
    return th ? th.outerHTML : '';
  }

  function termStartFromHead(head) {
    try { return kbTermStart('<table><thead>' + head + '</thead></table>'); } catch (e) { return ''; }
  }

  // 依次切到第 1..N 周，每切一次抓一次课表，最后按「同一天同一节同一门课」合并周次。
  g.__dshScanAllWeeks = function (totalWeeks) {
    return new Promise(function (resolve) {
      var bar = document.querySelectorAll('#zxkbWeeks > div');
      var tw = (bar && bar.length >= 4) ? bar.length : (totalWeeks || 16);
      var head = liveHead();
      var origWeek = 0;
      var m0 = (head || '').match(/(\d{1,2})\s*周/);
      if (m0) origWeek = +m0[1];
      var weekly = [], periods = [];
      function restore() {
        if (origWeek > 0 && typeof window.getGrkbInfo === 'function') {
          try { window.getGrkbInfo({ type: 1, zc: origWeek }); } catch (e) { }
        }
      }
      function finish() {
        var courses = kbUnionWeeks(weekly, tw);
        if (!courses.length) {
          var one = scan(null, null, tw);
          if (one && one.courses && one.courses.length) courses = one.courses;
          if (one && one.periods && one.periods.length && !periods.length) periods = one.periods;
        }
        restore();
        resolve({
          ok: courses.length > 0, count: courses.length, source: 'weeks',
          url: location.href, title: document.title, totalWeeks: tw,
          termStart: termStartFromHead(head), periods: periods, courses: courses
        });
      }
      function step(w) {
        if (w > tw) { finish(); return; }
        if (window.__dshScanCancel) {
          window.__dshScanCancel = false;
          restore();
          resolve({ ok: false, count: 0, source: 'cancelled', courses: [], periods: [] });
          return;
        }
        try { if (window.Dsh && window.Dsh.progress) window.Dsh.progress(w, tw); } catch (e) { }
        if (typeof window.getGrkbInfo !== 'function') { finish(); return; }
        var before = liveBody();
        try { window.getGrkbInfo({ type: 1, zc: w }); } catch (e) { }
        var tries = 0, done = false;
        var timer = setInterval(function () {
          tries++;
          var body = liveBody();
          if (body && body !== before) {
            clearInterval(timer); done = true;
            var html = '<table><thead>' + head + '</thead><tbody id="kbInfo">' + body + '</tbody></table>';
            var list = [];
            try { list = parseKbxx(html, tw); } catch (e) { list = []; }
            if (list.periods && list.periods.length && !periods.length) periods = list.periods;
            weekly.push({ w: w, list: list });
            setTimeout(function () { step(w + 1); }, 150);
          } else if (tries > 50) {
            clearInterval(timer); done = true;
            weekly.push({ w: w, list: [] });
            step(w + 1);
          }
        }, 200);
      }
      step(1);
    });
  };

  g.__dshScan = function (payload, periodsFromPage, totalWeeks) {
    return scan(payload, periodsFromPage, totalWeeks);
  };
  g.__dshParseTests = { parseText: parseText, parseOneTable: parseOneTable, tableRows: tableRows, parseKbxx: parseKbxx, mergeAdjacent: mergeAdjacent, collect: collect, kbUnionWeeks: kbUnionWeeks, weeksSpec: weeksSpec };
})(typeof window !== 'undefined' ? window : this);