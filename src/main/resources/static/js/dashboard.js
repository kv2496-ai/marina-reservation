async function loadDashboard() {
    try {
        const s = await Api.get('/api/dashboard');
        document.getElementById('asOf').textContent = 'As of ' + s.asOf;
        document.getElementById('statArrivals').textContent = s.arrivalsNext7Days.length;
        document.getElementById('statDepartures').textContent = s.departuresNext7Days.length;
        document.getElementById('statOccupied').textContent = s.occupiedNow.length;
        document.getElementById('statConflicts').textContent = s.unresolvedHardConflicts.length;

        fillReservationTable('arrivalsTable', s.arrivalsNext7Days, r => r.startDate);
        fillReservationTable('departuresTable', s.departuresNext7Days, r => r.endDate);

        const freeBody = document.querySelector('#freeBerthsTable tbody');
        freeBody.innerHTML = s.freeBerthsNow.map(b =>
            `<tr><td data-label="Berth">${escapeHtml(b.name)}</td><td data-label="Length">${b.lengthFt ? b.lengthFt + "'" : '<span class="muted">unknown</span>'}</td></tr>`
        ).join('') || '<tr><td colspan="2" class="muted">None</td></tr>';

        const hardList = document.getElementById('hardConflictList');
        hardList.innerHTML = s.unresolvedHardConflicts.map(f =>
            `<li class="hard">${escapeHtml(f.message)}</li>`
        ).join('') || '<li class="muted" style="border:none;background:none;">None 🎉</li>';

        const warnList = document.getElementById('warningList');
        warnList.innerHTML = s.unresolvedWarnings.slice(0, 50).map(f =>
            `<li class="${f.severity === 'DATA_QUALITY' ? 'data_quality' : ''}">[${f.type}] ${escapeHtml(f.message)}</li>`
        ).join('') || '<li class="muted" style="border:none;background:none;">None</li>';
    } catch (e) {
        toast('Failed to load dashboard: ' + e.message, 'error');
    }
}

function fillReservationTable(tableId, rows, dateFn) {
    const body = document.querySelector('#' + tableId + ' tbody');
    body.innerHTML = rows.map(r => `
        <tr>
            <td data-label="Date">${dateFn(r)}</td>
            <td data-label="Vessel / Event"><span class="badge kind-${r.kind.toLowerCase()}">${r.kind}</span> ${escapeHtml(r.vesselNameSnapshot || r.title || '')}</td>
            <td data-label="Berth">${escapeHtml(r.berthNameSnapshot || '')}</td>
            <td data-label="Status"><span class="badge status-${r.status.toLowerCase()}">${r.status}</span></td>
        </tr>`).join('') || `<tr><td colspan="4" class="muted">None</td></tr>`;
}

loadDashboard();
