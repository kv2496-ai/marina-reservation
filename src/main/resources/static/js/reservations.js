let resultRows = [];

async function init() {
    const berths = await Api.get('/api/berths');
    document.getElementById('fBerth').innerHTML += berths.map(b => `<option value="${b.id}">${escapeHtml(b.name)}</option>`).join('');
    ['VESSEL', 'COMMUNITY_EVENT', 'TOUR', 'MAINTENANCE', 'OTHER'].forEach(k => {
        document.getElementById('fKind').innerHTML += `<option value="${k}">${k}</option>`;
    });
    ['DRAFT', 'PENDING', 'CONFIRMED', 'CANCELED', 'COMPLETED'].forEach(s => {
        document.getElementById('fStatus').innerHTML += `<option value="${s}">${s}</option>`;
    });
    await search();
}

function buildQuery() {
    const params = new URLSearchParams();
    const q = document.getElementById('fQuery').value.trim();
    const berthId = document.getElementById('fBerth').value;
    const kind = document.getElementById('fKind').value;
    const status = document.getElementById('fStatus').value;
    const from = document.getElementById('fFrom').value;
    const to = document.getElementById('fTo').value;
    if (q) params.set('q', q);
    if (berthId) params.set('berthId', berthId);
    if (kind) params.set('kind', kind);
    if (status) params.set('status', status);
    if (from) params.set('from', from);
    if (to) params.set('to', to);
    return params;
}

async function search() {
    const params = buildQuery();
    resultRows = await Api.get('/api/reservations?' + params.toString());
    resultRows.sort((a, b) => (a.startDate || '').localeCompare(b.startDate || ''));
    document.getElementById('csvLink').href = '/api/export/reservations.csv?' + params.toString();
    document.getElementById('resultsHeading').textContent = `Results (${resultRows.length})`;

    const body = document.querySelector('#resultsTable tbody');
    body.innerHTML = resultRows.map(r => `
        <tr data-id="${r.id}" style="cursor:pointer">
            <td data-label="Start">${r.startDate}</td>
            <td data-label="End">${r.endDate}</td>
            <td data-label="Kind"><span class="badge kind-${r.kind.toLowerCase()}">${r.kind}</span></td>
            <td data-label="Vessel / Title">${escapeHtml(r.vesselNameSnapshot || r.title || '')}</td>
            <td data-label="Berth">${escapeHtml(r.berthNameSnapshot || '<span class="muted">unassigned</span>')}</td>
            <td data-label="Status"><span class="badge status-${r.status.toLowerCase()}">${r.status}</span></td>
            <td data-label="Rafting">${r.raftingApproved ? '✔' : ''}</td>
            <td data-label="Actions"><button class="secondary small-btn" data-del="${r.id}">Delete</button></td>
        </tr>`).join('') || '<tr><td colspan="8" class="muted">No matching reservations</td></tr>';

    body.querySelectorAll('tr[data-id]').forEach(tr => {
        tr.addEventListener('click', (e) => {
            if (e.target.closest('button')) return;
            const r = resultRows.find(x => x.id === tr.dataset.id);
            ReservationForm.open(r, null, search);
        });
    });
    body.querySelectorAll('button[data-del]').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            e.stopPropagation();
            if (!confirmDestructive('Permanently delete this reservation? This cannot be undone (use Cancel instead to keep history).')) return;
            try {
                await Api.del(`/api/reservations/${btn.dataset.del}`);
                toast('Reservation deleted', 'success');
                search();
            } catch (err) {
                toast('Failed: ' + err.message, 'error');
            }
        });
    });
}

document.getElementById('searchBtn').addEventListener('click', search);
document.getElementById('clearBtn').addEventListener('click', () => {
    document.querySelectorAll('#fQuery,#fFrom,#fTo').forEach(el => el.value = '');
    document.querySelectorAll('#fBerth,#fKind,#fStatus').forEach(el => el.value = '');
    search();
});
document.getElementById('newBtn').addEventListener('click', () => ReservationForm.open(null, null, search));

document.getElementById('availBtn').addEventListener('click', async () => {
    const start = document.getElementById('availStart').value;
    const end = document.getElementById('availEnd').value;
    const loa = document.getElementById('availLoa').value;
    if (!start || !end) { toast('Pick a start and end date', 'error'); return; }
    const params = new URLSearchParams({ start, end });
    if (loa) params.set('loaFt', loa);
    try {
        const suggestions = await Api.get('/api/availability/suggest?' + params.toString());
        const body = document.querySelector('#availTable tbody');
        body.innerHTML = suggestions.map(s => `
            <tr>
                <td data-label="Berth">${escapeHtml(s.berth.name)}</td>
                <td data-label="Length">${s.berth.lengthFt ? s.berth.lengthFt + "'" : '<span class="muted">unknown</span>'}</td>
                <td data-label="Fits LOA">${s.lengthCompatible ? '✔' : '✘'}</td>
                <td data-label="Conflict">${s.hasConfirmedConflict ? '⚠ yes' : 'no'}</td>
                <td data-label="Note" class="small muted">${escapeHtml(s.note || '')}</td>
            </tr>`).join('');
    } catch (e) {
        toast('Failed: ' + e.message, 'error');
    }
});

init();
