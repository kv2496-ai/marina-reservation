async function loadVessels(q) {
    const vessels = await Api.get('/api/vessels?q=' + encodeURIComponent(q || ''));
    vessels.sort((a, b) => (a.name || '').localeCompare(b.name || ''));
    const body = document.querySelector('#vesselTable tbody');
    body.innerHTML = vessels.map(v => `
        <tr data-id="${v.id}">
            <td>${escapeHtml(v.name)}</td>
            <td>${escapeHtml(v.type || '')}</td>
            <td>${v.loaFt ?? '<span class="muted">?</span>'}</td>
            <td>${v.draftFt ?? '<span class="muted">?</span>'}</td>
            <td>${escapeHtml(v.operator || '')}</td>
            <td>${escapeHtml(v.contactName || '')}</td>
            <td>${escapeHtml(v.phone || '')}</td>
            <td>${escapeHtml(v.email || '')}</td>
            <td>${v.dataQuality && v.dataQuality.flaggedForReview ? '<span class="badge severity-data_quality">review</span>' : ''}</td>
            <td>
                <button class="secondary" data-edit="${v.id}">Edit</button>
                <button class="danger" data-del="${v.id}">Delete</button>
            </td>
        </tr>`).join('') || '<tr><td colspan="10" class="muted">No vessels</td></tr>';

    body.querySelectorAll('[data-edit]').forEach(btn => btn.addEventListener('click', () => openVesselForm(vessels.find(v => v.id === btn.dataset.edit))));
    body.querySelectorAll('[data-del]').forEach(btn => btn.addEventListener('click', async () => {
        if (!confirmDestructive('Delete this vessel record? Existing reservations will keep a name snapshot but lose the link.')) return;
        try {
            await Api.del('/api/vessels/' + btn.dataset.del);
            toast('Vessel deleted', 'success');
            loadVessels(document.getElementById('vesselSearch').value);
        } catch (e) {
            toast('Failed: ' + e.message, 'error');
        }
    }));
}

function openVesselForm(existing) {
    const v = existing || {};
    const dq = v.dataQuality || {};
    const container = document.getElementById('detailModal');
    container.innerHTML = `
    <div class="modal-backdrop" id="vModalBackdrop">
      <div class="modal">
        <h2>${existing ? 'Edit vessel' : 'New vessel'}</h2>
        ${dq.flaggedForReview ? `<p class="small" style="color:var(--color-warning)">Flagged: ${escapeHtml(dq.reviewReason || '')}</p>` : ''}
        <form id="vesselForm">
          <label>Name</label><input name="name" value="${escapeHtml(v.name || '')}" required>
          <div class="row-2">
            <div><label>Type</label><input name="type" value="${escapeHtml(v.type || '')}"></div>
            <div><label>LOA (ft)</label><input type="number" step="0.1" name="loaFt" value="${v.loaFt ?? ''}"></div>
          </div>
          <div class="row-2">
            <div><label>Draft (ft)</label><input type="number" step="0.1" name="draftFt" value="${v.draftFt ?? ''}"></div>
            <div><label>Operator</label><input name="operator" value="${escapeHtml(v.operator || '')}"></div>
          </div>
          <div class="row-2">
            <div><label>Contact name</label><input name="contactName" value="${escapeHtml(v.contactName || '')}"></div>
            <div><label>Phone</label><input name="phone" value="${escapeHtml(v.phone || '')}"></div>
          </div>
          <label>Email</label><input type="email" name="email" value="${escapeHtml(v.email || '')}">
          <label>Notes / special arrangements</label><textarea name="notes" rows="3">${escapeHtml(v.notes || '')}</textarea>
          <div class="actions">
            <button type="button" class="secondary" id="vCancel">Cancel</button>
            <button type="submit">${existing ? 'Save' : 'Create'}</button>
          </div>
          <p class="small muted" id="vFormError"></p>
        </form>
      </div>
    </div>`;

    document.getElementById('vCancel').addEventListener('click', () => container.innerHTML = '');
    document.getElementById('vModalBackdrop').addEventListener('click', (e) => { if (e.target.id === 'vModalBackdrop') container.innerHTML = ''; });

    document.getElementById('vesselForm').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const fd = new FormData(ev.target);
        const payload = {
            name: fd.get('name'),
            type: fd.get('type') || null,
            loaFt: fd.get('loaFt') ? parseFloat(fd.get('loaFt')) : null,
            draftFt: fd.get('draftFt') ? parseFloat(fd.get('draftFt')) : null,
            operator: fd.get('operator') || null,
            contactName: fd.get('contactName') || null,
            phone: fd.get('phone') || null,
            email: fd.get('email') || null,
            notes: fd.get('notes') || null,
            dataQuality: existing ? existing.dataQuality : undefined,
        };
        try {
            if (existing) await Api.put('/api/vessels/' + existing.id, payload);
            else await Api.post('/api/vessels', payload);
            toast('Saved', 'success');
            container.innerHTML = '';
            loadVessels(document.getElementById('vesselSearch').value);
        } catch (e) {
            document.getElementById('vFormError').textContent = e.message;
        }
    });
}

document.getElementById('vesselSearch').addEventListener('input', (e) => loadVessels(e.target.value));
document.getElementById('newVesselBtn').addEventListener('click', () => openVesselForm(null));
loadVessels('');
