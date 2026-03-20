const historyStorageKey = 'nfc-scan-history';
const scanHistory = loadHistory();

function showTab(tabName) {
  const isScanner = tabName === 'scanner';

  document.getElementById('scanner-tab').hidden = !isScanner;
  document.getElementById('history-tab').hidden = isScanner;
  document.getElementById('scanner-button').classList.toggle('active', isScanner);
  document.getElementById('history-button').classList.toggle('active', !isScanner);
}

function getTime() {
  return new Date().toLocaleTimeString();
}
function getDate() {
  return new Date().toLocaleDateString();
}

function loadHistory() {
  try {
    const rawHistory = localStorage.getItem(historyStorageKey);
    return rawHistory ? JSON.parse(rawHistory) : [];
  } catch (error) {
    console.error('Error loading scan history:', error);
    return [];
  }
}

function saveHistory() {
  try {
    localStorage.setItem(historyStorageKey, JSON.stringify(scanHistory));
  } catch (error) {
    console.error('Error saving scan history:', error);
  }
}

function exportHistory() {
  if (!scanHistory || scanHistory.length === 0) {
    alert('No scans to export');
    return;
  }

  const headers = ['Location', 'Date', 'Time'];
  const rows = scanHistory.map(entry => [entry.locationLabel, entry.date, entry.time]);

  // escape and join into CSV
  const csvLines = [headers.join(',')].concat(
    rows.map(r => r.map(cell => '"' + String(cell).replace(/"/g, '""') + '"').join(','))
  );
  const csv = csvLines.join('\n');

  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  const datePart = new Date().toISOString().slice(0,10);
  a.download = `nfc-history-${datePart}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function renderHistory() {
  const historyLastScan = document.getElementById('history-last-scan');
  const historyLogBody = document.getElementById('history-log-body');

  if (scanHistory.length === 0) {
    historyLastScan.textContent = 'No scans yet.';
    historyLogBody.innerHTML = '<tr><td colspan="3">No scans recorded yet.</td></tr>';
    return;
  }

  const latestScan = scanHistory[scanHistory.length - 1];
  historyLastScan.textContent = latestScan.locationLabel + ' scanned on ' + latestScan.date + ' at ' + latestScan.time + '.';

  // Also update the scanner "Last scan" summary so the Scanner tab shows the latest stored scan
  const lastScanElem = document.getElementById('last-scan');
  if (lastScanElem) {
    lastScanElem.textContent = 'Last scan: ' + latestScan.locationLabel + ' (' + latestScan.date + ' ' + latestScan.time + ')';
  }

  // Populate "Latest by Location" rows from the most recent entry per point
  const latestByPoint = {};
  for (const entry of scanHistory) {
    latestByPoint[entry.pointID] = entry; // later entries overwrite earlier ones
  }
  if (latestByPoint['point-a']) {
    document.getElementById('point-a-date').textContent = latestByPoint['point-a'].date;
    document.getElementById('point-a-time').textContent = latestByPoint['point-a'].time;
  }
  if (latestByPoint['point-b']) {
    document.getElementById('point-b-date').textContent = latestByPoint['point-b'].date;
    document.getElementById('point-b-time').textContent = latestByPoint['point-b'].time;
  }

  historyLogBody.innerHTML = scanHistory
    .slice()
    .reverse()
    .map((entry) => '<tr><td>' + entry.locationLabel + '</td><td>' + entry.date + '</td><td>' + entry.time + '</td></tr>')
    .join('');
}

function updatePoint(pointID) {
  const date = document.getElementById(pointID + '-date').textContent = getDate();
  const time = document.getElementById(pointID + '-time').textContent = getTime();
  const locationLabel = pointID === 'point-a' ? 'Point A' : 'Point B';

  scanHistory.push({ pointID, locationLabel, date, time });
  saveHistory();
  renderHistory();

  if (typeof require === 'function') {
    const fs = require('fs');
    try {
      fs.writeFileSync('log.txt', `${pointID} scanned at ${date} ${time}\n`, { flag: 'a' });
    }
    catch (err) {
      console.error('Error writing to log file:', err);
    }
  }
}

async function startNFC() {
  try {
    const reader = new NDEFReader();
    await reader.scan();
    document.getElementById('nfc-status').textContent = 'NFC ready — scan a tag';
    reader.addEventListener('reading', ({ message }) => {
      for (const record of message.records) {
        let text = '';
        if (record.recordType === 'text') {
          text = new TextDecoder(record.encoding || 'utf-8').decode(record.data);
        } else {
          text = new TextDecoder().decode(record.data);
        }
        text = text.trim();
        document.getElementById('nfc-status').textContent = 'Last scan: "' + text + '"';
        document.getElementById('last-scan').textContent = 'Last scan: ' + text;
        if (text === 'point-a') updatePoint('point-a');
        else if (text === 'point-b') updatePoint('point-b');
      }
    });
    reader.addEventListener('readingerror', () => {
      document.getElementById('nfc-status').textContent = 'Error reading tag — try again';
    });
  } catch (error) {
    document.getElementById('nfc-status').textContent = 'NFC error: ' + error;
  }
}

renderHistory();
