let allBerths = [];
let allReservations = [];
let reservationById = new Map();
let viewMode = window.matchMedia('(max-width: 700px)').matches ? 'list' : 'grid';

function currentMonthValue() {
    const picker = document.getElementById('monthPicker');
    if (picker.value) return picker.value;
    const now = new Date();
    return now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
}

function daysInMonth(year, month) {
    return new Date(year, month, 0).getDate();
}

function dateStr(year, month, day) {
    return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

async function renderCalendar() {
    const [year, month] = currentMonthValue().split('-').map(Number);
    const monthStart = dateStr(year, month, 1);
    const monthEnd = dateStr(year, month, daysInMonth(year, month));

    document.getElementById('monthTitle').textContent =
        new Date(year, month - 1, 1).toLocaleString('en-US', { month: 'long', year: 'numeric' }) + ' — Dock Schedule';
    document.getElementById('exportCsvLink').href = `/api/export/reservations.csv?from=${monthStart}&to=${monthEnd}`;

    [allBerths, allReservations] = await Promise.all([
        Api.get('/api/berths'),
        Api.get(`/api/reservations?from=${monthStart}&to=${monthEnd}`),
    ]);
    reservationById = new Map(allReservations.map(r => [r.id, r]));

    applyViewMode();
    if (viewMode === 'grid') {
        renderGridView(year, month);
    } else {
        renderListView(year, month);
    }
}

function applyViewMode() {
    document.getElementById('gridViewWrap').style.display = viewMode === 'grid' ? '' : 'none';
    document.getElementById('listViewWrap').style.display = viewMode === 'list' ? '' : 'none';
    document.getElementById('gridViewBtn').classList.toggle('active', viewMode === 'grid');
    document.getElementById('listViewBtn').classList.toggle('active', viewMode === 'list');
}

function setViewMode(mode) {
    viewMode = mode;
    applyViewMode();
    const [year, month] = currentMonthValue().split('-').map(Number);
    if (mode === 'grid') renderGridView(year, month); else renderListView(year, month);
}

// ---------- Grid view (desktop) ----------

function renderGridView(year, month) {
    const nDays = daysInMonth(year, month);
    const table = document.getElementById('calendarTable');

    let headHtml = '<thead><tr><th>Berth</th>';
    for (let d = 1; d <= nDays; d++) {
        const dow = new Date(year, month - 1, d).getDay();
        const weekend = (dow === 0 || dow === 6) ? ' weekend' : '';
        headHtml += `<th class="day-head${weekend}">${d}</th>`;
    }
    headHtml += '</tr></thead>';

    let bodyHtml = '<tbody>';
    for (const berth of allBerths) {
        const resForBerth = allReservations.filter(r => r.berthId === berth.id && r.status !== 'CANCELED');
        bodyHtml += `<tr><td class="berth-label">${escapeHtml(berth.name)}${berth.lengthFt ? ' <span class="muted small">('+berth.lengthFt+"')</span>" : ''}</td>`;
        bodyHtml += renderBerthDays(berth, year, month, nDays, resForBerth);
        bodyHtml += '</tr>';
    }
    bodyHtml += '</tbody>';

    table.innerHTML = headHtml + bodyHtml;

    table.querySelectorAll('td.day-cell').forEach(td => {
        td.addEventListener('click', () => {
            const id = td.dataset.resId;
            if (id) {
                ReservationForm.open(reservationById.get(id), null, renderCalendar);
            } else {
                ReservationForm.open(null, { berthId: td.dataset.berth, startDate: td.dataset.date, endDate: td.dataset.date }, renderCalendar);
            }
        });
    });
}

function renderBerthDays(berth, year, month, nDays, reservations) {
    let html = '';
    let day = 1;
    while (day <= nDays) {
        const date = dateStr(year, month, day);
        const active = reservations.filter(r => r.startDate <= date && r.endDate >= date);

        if (active.length === 0) {
            html += `<td class="day-cell empty" data-berth="${berth.id}" data-date="${date}"></td>`;
            day += 1;
            continue;
        }
        if (active.length === 1) {
            const r = active[0];
            let span = 1;
            while (day + span <= nDays) {
                const d2 = dateStr(year, month, day + span);
                const stillActive = reservations.filter(r2 => r2.startDate <= d2 && r2.endDate >= d2);
                if (stillActive.length === 1 && stillActive[0].id === r.id) span++; else break;
            }
            html += cellHtml(r, span, false);
            day += span;
            continue;
        }
        html += cellHtml(active[0], 1, true);
        day += 1;
    }
    return html;
}

function cellHtml(r, span, conflict) {
    const kindColor = `var(--kind-${r.kind.toLowerCase()})`;
    const statusAlpha = statusAlphaFor(r.status);
    const textColor = statusAlpha < 0.6 ? '#1f2933' : '#fff';
    const label = escapeHtml(r.vesselNameSnapshot || r.title || r.kind);
    return `<td class="day-cell" colspan="${span}" data-res-id="${r.id}"
        style="background:color-mix(in srgb, ${kindColor} ${Math.round(statusAlpha * 100)}%, white); color:${textColor};"
        title="${escapeHtml(r.kind)} — ${label} (${r.status})">${conflict ? '<span class="conflict-mark" style="color:#b3261e">⚠</span>' : ''}${label}</td>`;
}

function statusAlphaFor(status) {
    return { DRAFT: 0.45, PENDING: 0.7, CONFIRMED: 1, CANCELED: 0.25, COMPLETED: 0.55 }[status] ?? 1;
}

// ---------- List / agenda view (mobile) ----------

function renderListView(year, month) {
    const wrap = document.getElementById('listViewWrap');
    const berthById = new Map(allBerths.map(b => [b.id, b]));

    const active = allReservations
        .filter(r => r.status !== 'CANCELED')
        .slice()
        .sort((a, b) => (a.startDate || '').localeCompare(b.startDate || '') || (a.berthNameSnapshot || '').localeCompare(b.berthNameSnapshot || ''));

    // Conflict lookup: any reservation sharing a berth + overlapping dates with another active one.
    const conflictIds = new Set();
    for (let i = 0; i < active.length; i++) {
        for (let j = i + 1; j < active.length; j++) {
            const a = active[i], b = active[j];
            if (a.berthId && a.berthId === b.berthId && a.startDate <= b.endDate && b.startDate <= a.endDate) {
                conflictIds.add(a.id);
                conflictIds.add(b.id);
            }
        }
    }

    if (active.length === 0) {
        wrap.innerHTML = '<p class="muted">No reservations this month.</p>';
        return;
    }

    const groups = new Map(); // startDate -> reservations[]
    for (const r of active) {
        const key = r.startDate || '(no date)';
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(r);
    }

    let html = '';
    for (const [date, rows] of groups) {
        const label = new Date(date + 'T00:00:00').toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
        html += `<div class="agenda-day"><div class="agenda-date">${escapeHtml(label)}</div>`;
        for (const r of rows) {
            const berth = berthById.get(r.berthId);
            const kindColor = `var(--kind-${r.kind.toLowerCase()})`;
            const label2 = escapeHtml(r.vesselNameSnapshot || r.title || r.kind);
            const span = r.startDate === r.endDate ? r.startDate : `${r.startDate} → ${r.endDate}`;
            const conflict = conflictIds.has(r.id) ? '<span class="conflict-mark" style="color:var(--color-danger)">⚠ </span>' : '';
            html += `
                <div class="agenda-card" data-res-id="${r.id}">
                    <div class="agenda-color" style="background:${kindColor}; opacity:${statusAlphaFor(r.status)};"></div>
                    <div class="agenda-body">
                        <div class="agenda-title">${conflict}${label2}</div>
                        <div class="agenda-meta">${escapeHtml(berth ? berth.name : (r.berthNameSnapshot || 'unassigned'))} · ${span} ·
                            <span class="badge status-${r.status.toLowerCase()}">${r.status}</span></div>
                    </div>
                </div>`;
        }
        html += '</div>';
    }
    wrap.innerHTML = html;

    wrap.querySelectorAll('.agenda-card').forEach(card => {
        card.addEventListener('click', () => {
            ReservationForm.open(reservationById.get(card.dataset.resId), null, renderCalendar);
        });
    });
}

document.getElementById('prevMonth').addEventListener('click', () => shiftMonth(-1));
document.getElementById('nextMonth').addEventListener('click', () => shiftMonth(1));
document.getElementById('monthPicker').addEventListener('change', renderCalendar);
document.getElementById('newReservationBtn').addEventListener('click', () => ReservationForm.open(null, null, renderCalendar));
document.getElementById('gridViewBtn').addEventListener('click', () => setViewMode('grid'));
document.getElementById('listViewBtn').addEventListener('click', () => setViewMode('list'));

function shiftMonth(delta) {
    const [year, month] = currentMonthValue().split('-').map(Number);
    const d = new Date(year, month - 1 + delta, 1);
    document.getElementById('monthPicker').value = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
    renderCalendar();
}

document.getElementById('monthPicker').value = currentMonthValue();
renderCalendar();
