const ReservationForm = (() => {
    let berthsCache = null;
    let vesselByName = new Map();

    async function ensureBerths() {
        if (!berthsCache) berthsCache = await Api.get('/api/berths');
        return berthsCache;
    }

    async function vesselOptionsHtml(query) {
        const vessels = await Api.get('/api/vessels?q=' + encodeURIComponent(query || ''));
        vesselByName = new Map(vessels.map(v => [v.name, v]));
        return vessels.map(v => `<option value="${escapeHtml(v.name)}">`).join('');
    }

    async function open(existing, defaults, onSaved) {
        const berths = await ensureBerths();
        const isEdit = !!existing;
        const r = existing || Object.assign({
            kind: 'VESSEL', status: 'CONFIRMED', raftingApproved: false, recurring: false,
        }, defaults || {});

        const idemKey = newIdempotencyKey();

        const container = document.getElementById('detailModal');
        container.innerHTML = `
        <div class="modal-backdrop" id="modalBackdrop">
          <div class="modal">
            <h2>${isEdit ? 'Edit reservation' : 'New reservation'}</h2>
            <form id="resForm">
              <div class="row-2">
                <div>
                  <label>Kind</label>
                  <select name="kind" id="kindSelect">
                    ${['VESSEL','COMMUNITY_EVENT','TOUR','MAINTENANCE','OTHER'].map(k =>
                        `<option value="${k}" ${r.kind===k?'selected':''}>${k}</option>`).join('')}
                  </select>
                </div>
                <div>
                  <label>Status</label>
                  <select name="status">
                    ${['DRAFT','PENDING','CONFIRMED','CANCELED','COMPLETED'].map(s =>
                        `<option value="${s}" ${r.status===s?'selected':''}>${s}</option>`).join('')}
                  </select>
                </div>
              </div>

              <div id="vesselField">
                <label>Vessel (autocomplete)</label>
                <input type="text" name="vesselName" list="vesselOptions" value="${escapeHtml(r.vesselNameSnapshot || '')}" autocomplete="off">
                <datalist id="vesselOptions"></datalist>
              </div>
              <div id="titleField" style="display:none">
                <label>Title</label>
                <input type="text" name="title" value="${escapeHtml(r.title || '')}">
              </div>

              <label>Berth</label>
              <select name="berthId">
                <option value="">— unassigned —</option>
                ${berths.map(b => `<option value="${b.id}" ${r.berthId===b.id?'selected':''}>${escapeHtml(b.name)}${b.lengthFt ? ' ('+b.lengthFt+"')" : ''}</option>`).join('')}
              </select>

              <div class="row-2">
                <div><label>Start date</label><input type="date" name="startDate" value="${r.startDate||''}" required></div>
                <div><label>End date</label><input type="date" name="endDate" value="${r.endDate||''}" required></div>
              </div>
              <div class="row-2">
                <div><label>Start time (optional)</label><input type="time" name="startTime" value="${r.startTime||''}"></div>
                <div><label>End time (optional)</label><input type="time" name="endTime" value="${r.endTime||''}"></div>
              </div>

              <label><input type="checkbox" name="raftingApproved" style="width:auto;display:inline-block;vertical-align:-2px;" ${r.raftingApproved?'checked':''}> Rafting / shared-berth approved (overlaps with another rafting-approved reservation are treated as a warning, not a hard conflict)</label>

              ${isEdit ? '' : `
              <label><input type="checkbox" id="recurringToggle" style="width:auto;display:inline-block;vertical-align:-2px;"> Recurring series</label>
              <div id="recurrenceFields" style="display:none">
                <div class="row-2">
                  <div><label>Frequency</label><select id="recFrequency"><option value="DAILY">Daily</option><option value="WEEKLY" selected>Weekly</option><option value="MONTHLY">Monthly</option></select></div>
                  <div><label>Every N</label><input type="number" id="recInterval" value="1" min="1"></div>
                </div>
                <div class="row-2">
                  <div><label>Repeat count</label><input type="number" id="recCount" value="8" min="1"></div>
                  <div><label>Until (optional)</label><input type="date" id="recUntil"></div>
                </div>
              </div>`}

              <label>Notes</label>
              <textarea name="notes" rows="3">${escapeHtml(r.notes || '')}</textarea>

              <div class="actions">
                <button type="button" class="secondary" id="cancelFormBtn">Cancel</button>
                ${isEdit ? '<button type="button" class="danger" id="cancelResBtn">Cancel reservation</button>' : ''}
                <button type="submit" id="submitBtn">${isEdit ? 'Save changes' : 'Create'}</button>
              </div>
              <p class="small muted" id="formError"></p>
            </form>
          </div>
        </div>`;

        vesselOptionsHtml('').then(html => document.getElementById('vesselOptions').innerHTML = html);
        const vesselInput = container.querySelector('input[name=vesselName]');
        vesselInput.addEventListener('input', async () => {
            document.getElementById('vesselOptions').innerHTML = await vesselOptionsHtml(vesselInput.value);
        });

        function syncKindFields() {
            const kind = container.querySelector('#kindSelect').value;
            container.querySelector('#vesselField').style.display = kind === 'VESSEL' ? '' : 'none';
            container.querySelector('#titleField').style.display = kind === 'VESSEL' ? 'none' : '';
        }
        container.querySelector('#kindSelect').addEventListener('change', syncKindFields);
        syncKindFields();

        const recurringToggle = container.querySelector('#recurringToggle');
        if (recurringToggle) {
            recurringToggle.addEventListener('change', () => {
                container.querySelector('#recurrenceFields').style.display = recurringToggle.checked ? '' : 'none';
            });
        }

        container.querySelector('#cancelFormBtn').addEventListener('click', close);
        container.querySelector('#modalBackdrop').addEventListener('click', (e) => {
            if (e.target.id === 'modalBackdrop') close();
        });

        if (isEdit) {
            container.querySelector('#cancelResBtn').addEventListener('click', async () => {
                if (!confirmDestructive('Cancel this reservation? It will be kept for history but marked CANCELED.')) return;
                try {
                    await Api.post(`/api/reservations/${existing.id}/cancel`);
                    toast('Reservation canceled', 'success');
                    close();
                    onSaved && onSaved();
                } catch (e) {
                    toast('Failed: ' + e.message, 'error');
                }
            });
        }

        const form = container.querySelector('#resForm');
        form.addEventListener('submit', async (ev) => {
            ev.preventDefault();
            const submitBtn = container.querySelector('#submitBtn');
            submitBtn.disabled = true;
            const fd = new FormData(form);
            const kind = fd.get('kind');

            const payload = {
                kind,
                status: fd.get('status'),
                berthId: fd.get('berthId') || null,
                startDate: fd.get('startDate'),
                endDate: fd.get('endDate'),
                startTime: fd.get('startTime') || null,
                endTime: fd.get('endTime') || null,
                raftingApproved: fd.get('raftingApproved') === 'on',
                notes: fd.get('notes') || null,
            };
            if (kind === 'VESSEL') {
                const name = fd.get('vesselName');
                const vessel = vesselByName.get(name);
                if (vessel) {
                    payload.vesselId = vessel.id;
                } else if (name) {
                    try {
                        const created = await Api.post('/api/vessels', { name });
                        payload.vesselId = created.id;
                    } catch (e) {
                        document.getElementById('formError').textContent = 'Could not create vessel: ' + e.message;
                        submitBtn.disabled = false;
                        return;
                    }
                }
            } else {
                payload.title = fd.get('title');
            }

            try {
                let result;
                if (isEdit) {
                    result = await Api.put(`/api/reservations/${existing.id}`, payload);
                } else if (recurringToggle && recurringToggle.checked) {
                    payload.recurring = true;
                    payload.recurrenceRule = {
                        frequency: document.getElementById('recFrequency').value,
                        interval: parseInt(document.getElementById('recInterval').value || '1', 10),
                        count: parseInt(document.getElementById('recCount').value || '1', 10),
                        until: document.getElementById('recUntil').value || null,
                    };
                    result = await Api.post('/api/reservations/series', payload, { 'Idempotency-Key': idemKey });
                } else {
                    result = await Api.post('/api/reservations', payload, { 'Idempotency-Key': idemKey });
                }
                toast(isEdit ? 'Reservation updated' : 'Reservation created', 'success');
                close();
                onSaved && onSaved(result);
            } catch (e) {
                document.getElementById('formError').textContent = e.message;
                submitBtn.disabled = false;
            }
        });
    }

    function close() {
        document.getElementById('detailModal').innerHTML = '';
    }

    return { open, close };
})();
