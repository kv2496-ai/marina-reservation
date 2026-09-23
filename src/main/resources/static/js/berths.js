async function loadBerths() {
    const berths = await Api.get('/api/berths');
    const body = document.querySelector('#berthTable tbody');
    body.innerHTML = berths.map(b => `
        <tr>
            <td>${escapeHtml(b.name)} ${b.unverified ? '<span class="badge severity-data_quality">unverified</span>' : ''}</td>
            <td>${b.lengthFt ? b.lengthFt + "'" : '<span class="muted">unknown</span>'}</td>
            <td><span class="badge status-${b.status === 'ACTIVE' ? 'confirmed' : 'canceled'}">${b.status}</span></td>
            <td class="small">${escapeHtml(b.notes || '')}</td>
            <td>
                <button class="secondary" data-edit="${b.id}">Edit</button>
                <button class="danger" data-del="${b.id}">Delete</button>
            </td>
        </tr>`).join('');

    body.querySelectorAll('[data-edit]').forEach(btn => btn.addEventListener('click', () => openBerthForm(berths.find(b => b.id === btn.dataset.edit))));
    body.querySelectorAll('[data-del]').forEach(btn => btn.addEventListener('click', async () => {
        if (!confirmDestructive('Delete this berth? Existing reservations will keep a name snapshot but lose the link.')) return;
        try {
            await Api.del('/api/berths/' + btn.dataset.del);
            toast('Berth deleted', 'success');
            loadBerths();
        } catch (e) {
            toast('Failed: ' + e.message, 'error');
        }
    }));
}

function openBerthForm(existing) {
    const b = existing || { status: 'ACTIVE', restrictions: {} };
    const container = document.getElementById('detailModal');
    container.innerHTML = `
    <div class="modal-backdrop" id="bModalBackdrop">
      <div class="modal">
        <h2>${existing ? 'Edit berth' : 'New berth'}</h2>
        <form id="berthForm">
          <label>Name</label><input name="name" value="${escapeHtml(b.name || '')}" required>
          <div class="row-2">
            <div><label>Length (ft)</label><input type="number" step="0.1" name="lengthFt" value="${b.lengthFt ?? ''}"></div>
            <div><label>Status</label><select name="status">
                <option value="ACTIVE" ${b.status==='ACTIVE'?'selected':''}>ACTIVE</option>
                <option value="CLOSED" ${b.status==='CLOSED'?'selected':''}>CLOSED</option>
                <option value="RESTRICTED" ${b.status==='RESTRICTED'?'selected':''}>RESTRICTED</option>
            </select></div>
          </div>
          <label>Notes</label><textarea name="notes" rows="3">${escapeHtml(b.notes || '')}</textarea>
          <p class="small muted">Future restrictions (min draft, electrical, water, weather closures) can be
          added later without a schema change — this prototype's UI just doesn't expose that editor yet.</p>
          <div class="actions">
            <button type="button" class="secondary" id="bCancel">Cancel</button>
            <button type="submit">${existing ? 'Save' : 'Create'}</button>
          </div>
          <p class="small muted" id="bFormError"></p>
        </form>
      </div>
    </div>`;

    document.getElementById('bCancel').addEventListener('click', () => container.innerHTML = '');
    document.getElementById('bModalBackdrop').addEventListener('click', (e) => { if (e.target.id === 'bModalBackdrop') container.innerHTML = ''; });

    document.getElementById('berthForm').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const fd = new FormData(ev.target);
        const payload = {
            name: fd.get('name'),
            lengthFt: fd.get('lengthFt') ? parseFloat(fd.get('lengthFt')) : null,
            status: fd.get('status'),
            notes: fd.get('notes') || null,
            restrictions: existing ? existing.restrictions : {},
            unverified: existing ? existing.unverified : false,
        };
        try {
            if (existing) await Api.put('/api/berths/' + existing.id, payload);
            else await Api.post('/api/berths', payload);
            toast('Saved', 'success');
            container.innerHTML = '';
            loadBerths();
        } catch (e) {
            document.getElementById('bFormError').textContent = e.message;
        }
    });
}

document.getElementById('newBerthBtn').addEventListener('click', () => openBerthForm(null));
loadBerths();
