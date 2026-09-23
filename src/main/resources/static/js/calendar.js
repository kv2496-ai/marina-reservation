let allBerths = [];
let allReservations = [];
let reservationById = new Map();

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
    const statusAlpha = {
        DRAFT: 0.45, PENDING: 0.7, CONFIRMED: 1, CANCELED: 0.25, COMPLETED: 0.55,
    }[r.status] ?? 1;
    const textColor = statusAlpha < 0.6 ? '#1f2933' : '#fff';
    const label = escapeHtml(r.vesselNameSnapshot || r.title || r.kind);
    return `<td class="day-cell" colspan="${span}" data-res-id="${r.id}"
        style="background:color-mix(in srgb, ${kindColor} ${Math.round(statusAlpha * 100)}%, white); color:${textColor};"
        title="${escapeHtml(r.kind)} — ${label} (${r.status})">${conflict ? '<span class="conflict-mark" style="color:#b3261e">⚠</span>' : ''}${label}</td>`;
}

document.getElementById('prevMonth').addEventListener('click', () => shiftMonth(-1));
document.getElementById('nextMonth').addEventListener('click', () => shiftMonth(1));
document.getElementById('monthPicker').addEventListener('change', renderCalendar);
document.getElementById('newReservationBtn').addEventListener('click', () => ReservationForm.open(null, null, renderCalendar));

function shiftMonth(delta) {
    const [year, month] = currentMonthValue().split('-').map(Number);
    const d = new Date(year, month - 1 + delta, 1);
    document.getElementById('monthPicker').value = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
    renderCalendar();
}

document.getElementById('monthPicker').value = currentMonthValue();
renderCalendar();
