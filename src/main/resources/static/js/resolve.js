function todayIso() {
    const d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

async function loadAll() {
    const [flags, futureReservations] = await Promise.all([
        Api.get('/api/validation-flags?resolved=false'),
        Api.get('/api/reservations?from=' + todayIso()),
    ]);
    const actionableIds = new Set(futureReservations.map(r => r.id));

    await Promise.all([
        renderConflicts(flags.filter(f => f.type === 'DOUBLE_BOOKING' && f.severity === 'HARD_CONFLICT'), actionableIds),
        renderLoa(flags.filter(f => f.type === 'LOA_EXCEEDS_BERTH'), actionableIds),
        renderMissingData(flags.filter(f => f.type === 'MISSING_VESSEL_INFO'), actionableIds),
    ]);
    await Promise.all([renderWaitlist(), renderNotifications()]);
}

function historicalNote(elId, totalCount, shownCount) {
    const hidden = totalCount - shownCount;
    document.getElementById(elId).textContent = hidden > 0
        ? `${hidden} historical (already past) flagged reservation${hidden === 1 ? '' : 's'} not shown here — see Dashboard / Import Review.`
        : '';
}

// ---------- Hard conflicts ----------

async function renderConflicts(flags, actionableIds) {
    // One flag per side of each pair; keep only the lexicographically-first id to dedupe.
    const pairs = flags.filter(f => f.reservationId < f.relatedReservationId);
    const actionablePairs = pairs.filter(f => actionableIds.has(f.reservationId) || actionableIds.has(f.relatedReservationId));

    document.getElementById('conflictsCount').textContent = pairs.length;
    historicalNote('conflictsHistoricalNote', pairs.length, actionablePairs.length);

    const list = document.getElementById('conflictsList');
    if (actionablePairs.length === 0) {
        list.innerHTML = '<p class="empty-note">No actionable hard conflicts right now.</p>';
        return;
    }

    const cards = await Promise.all(actionablePairs.map(async (flag) => {
        const [a, b] = await Promise.all([
            Api.get('/api/reservations/' + flag.reservationId),
            Api.get('/api/reservations/' + flag.relatedReservationId),
        ]);
        return `
        <div class="resolve-card hard" data-flag-id="${flag.id}">
            <div class="rc-title">⚠ ${escapeHtml(a.vesselNameSnapshot || a.title)} vs. ${escapeHtml(b.vesselNameSnapshot || b.title)}</div>
            <div class="rc-meta">
                <div>Berth: ${escapeHtml(a.berthNameSnapshot || '')}</div>
                <div>A: ${escapeHtml(a.vesselNameSnapshot || a.title)} · ${a.startDate} → ${a.endDate} · <span class="badge kind-${a.kind.toLowerCase()}">${a.kind}</span></div>
                <div>B: ${escapeHtml(b.vesselNameSnapshot || b.title)} · ${b.startDate} → ${b.endDate} · <span class="badge kind-${b.kind.toLowerCase()}">${b.kind}</span></div>
            </div>
            <div class="rc-actions">
                <button data-resolve-conflict="${flag.id}">Resolve</button>
            </div>
            <div class="rc-result" style="display:none"></div>
        </div>`;
    }));
    list.innerHTML = cards.join('');

    list.querySelectorAll('[data-resolve-conflict]').forEach(btn => {
        btn.addEventListener('click', async () => {
            btn.disabled = true;
            const card = btn.closest('.resolve-card');
            const resultBox = card.querySelector('.rc-result');
            try {
                const result = await Api.post('/api/resolve/conflict/' + btn.dataset.resolveConflict);
                resultBox.style.display = '';
                resultBox.className = 'rc-result';
                resultBox.textContent = result.message;
                toast('Conflict resolved: ' + result.outcome, 'success');
                setTimeout(loadAll, 1500);
            } catch (e) {
                resultBox.style.display = '';
                resultBox.className = 'rc-result err';
                resultBox.textContent = 'Failed: ' + e.message;
                btn.disabled = false;
            }
        });
    });
}

// ---------- LOA exceeds berth ----------

async function renderLoa(flags, actionableIds) {
    const actionable = flags.filter(f => actionableIds.has(f.reservationId));
    document.getElementById('loaCount').textContent = flags.length;
    historicalNote('loaHistoricalNote', flags.length, actionable.length);

    const list = document.getElementById('loaList');
    if (actionable.length === 0) {
        list.innerHTML = '<p class="empty-note">No actionable oversized-vessel warnings right now.</p>';
        return;
    }

    const cards = await Promise.all(actionable.map(async (flag) => {
        const r = await Api.get('/api/reservations/' + flag.reservationId);
        return `
        <div class="resolve-card warn" data-flag-id="${flag.id}">
            <div class="rc-title">${escapeHtml(r.vesselNameSnapshot || '')} — too long for ${escapeHtml(r.berthNameSnapshot || '')}</div>
            <div class="rc-meta"><div>${r.startDate} → ${r.endDate}</div><div>${escapeHtml(flag.message)}</div></div>
            <div class="rc-actions"><button data-resolve-loa="${flag.id}">Resolve</button></div>
            <div class="rc-result" style="display:none"></div>
        </div>`;
    }));
    list.innerHTML = cards.join('');

    list.querySelectorAll('[data-resolve-loa]').forEach(btn => {
        btn.addEventListener('click', () => openLoaModal(btn.dataset.resolveLoa));
    });
}

async function openLoaModal(flagId) {
    const container = document.getElementById('detailModal');
    container.innerHTML = `
    <div class="modal-backdrop" id="loaModalBackdrop">
      <div class="modal">
        <h2>Alternative berths</h2>
        <p class="small muted" id="loaModalStatus">Checking availability…</p>
        <div id="loaModalOptions"></div>
        <div class="actions">
          <button type="button" class="secondary" id="loaModalCancel">Close</button>
        </div>
      </div>
    </div>`;
    document.getElementById('loaModalCancel').addEventListener('click', () => container.innerHTML = '');
    document.getElementById('loaModalBackdrop').addEventListener('click', (e) => { if (e.target.id === 'loaModalBackdrop') container.innerHTML = ''; });

    try {
        const alternatives = await Api.get(`/api/resolve/loa/${flagId}/alternatives`);
        const statusEl = document.getElementById('loaModalStatus');
        const optionsEl = document.getElementById('loaModalOptions');
        if (alternatives.length > 0) {
            statusEl.textContent = `${alternatives.length} berth(s) would fit this vessel for the same dates:`;
            optionsEl.innerHTML = '<ul>' + alternatives.map(b => `<li>${escapeHtml(b.name)} (${b.lengthFt}')</li>`).join('') + '</ul>'
                + '<button id="loaNotifyBtn">Log notification of these options</button>';
            document.getElementById('loaNotifyBtn').addEventListener('click', async () => {
                try {
                    const result = await Api.post(`/api/resolve/loa/${flagId}/notify`);
                    toast(result.message, 'success');
                    container.innerHTML = '';
                    loadAll();
                } catch (e) {
                    toast('Failed: ' + e.message, 'error');
                }
            });
        } else {
            statusEl.textContent = 'No berth currently fits this vessel for those dates.';
            optionsEl.innerHTML = '<button id="loaWaitlistBtn" class="danger">Cancel and move to waitlist</button>';
            document.getElementById('loaWaitlistBtn').addEventListener('click', async () => {
                if (!confirmDestructive('Cancel this reservation and move the vessel to the waitlist?')) return;
                try {
                    const result = await Api.post(`/api/resolve/loa/${flagId}/waitlist`);
                    toast(result.message, 'success');
                    container.innerHTML = '';
                    loadAll();
                } catch (e) {
                    toast('Failed: ' + e.message, 'error');
                }
            });
        }
    } catch (e) {
        document.getElementById('loaModalStatus').textContent = 'Failed to load: ' + e.message;
    }
}

// ---------- Missing vital data ----------

async function renderMissingData(flags, actionableIds) {
    const actionable = flags.filter(f => actionableIds.has(f.reservationId));
    document.getElementById('missingCount').textContent = flags.length;
    historicalNote('missingHistoricalNote', flags.length, actionable.length);

    const list = document.getElementById('missingList');
    if (actionable.length === 0) {
        list.innerHTML = '<p class="empty-note">No actionable missing-data flags right now.</p>';
        return;
    }

    const cards = await Promise.all(actionable.map(async (flag) => {
        const r = await Api.get('/api/reservations/' + flag.reservationId);
        return `
        <div class="resolve-card dq" data-flag-id="${flag.id}">
            <div class="rc-title">${escapeHtml(r.vesselNameSnapshot || '')} — insufficient vessel data</div>
            <div class="rc-meta"><div>${escapeHtml(r.berthNameSnapshot || '')} · ${r.startDate} → ${r.endDate}</div><div>${escapeHtml(flag.message)}</div></div>
            <div class="rc-actions"><button class="danger" data-resolve-missing="${flag.id}">Resolve (cancels reservation)</button></div>
            <div class="rc-result" style="display:none"></div>
        </div>`;
    }));
    list.innerHTML = cards.join('');

    list.querySelectorAll('[data-resolve-missing]').forEach(btn => {
        btn.addEventListener('click', async () => {
            if (!confirmDestructive('This vessel is missing vital information. The reservation will be canceled. Continue?')) return;
            btn.disabled = true;
            const card = btn.closest('.resolve-card');
            const resultBox = card.querySelector('.rc-result');
            try {
                const result = await Api.post('/api/resolve/missing-data/' + btn.dataset.resolveMissing);
                resultBox.style.display = '';
                resultBox.className = 'rc-result';
                resultBox.textContent = result.message;
                toast('Resolved: reservation canceled', 'success');
                setTimeout(loadAll, 1500);
            } catch (e) {
                resultBox.style.display = '';
                resultBox.className = 'rc-result err';
                resultBox.textContent = 'Failed: ' + e.message;
                btn.disabled = false;
            }
        });
    });
}

// ---------- Waitlist ----------

async function renderWaitlist() {
    const entries = await Api.get('/api/waitlist');
    const active = entries.filter(e => e.status === 'WAITING' || e.status === 'MATCHED');
    document.getElementById('waitlistCount').textContent = active.length;

    const list = document.getElementById('waitlistList');
    if (active.length === 0) {
        list.innerHTML = '<p class="empty-note">Nobody waiting.</p>';
        return;
    }
    list.innerHTML = active.map(w => `
        <div class="resolve-card ${w.status === 'MATCHED' ? 'warn' : ''}" data-id="${w.id}">
            <div class="rc-title">${escapeHtml(w.vesselNameSnapshot)}</div>
            <div class="rc-meta">
                <div>${w.startDate} → ${w.endDate} · LOA ${w.loaFt ?? '?'}'</div>
                <div>Bumped from: ${escapeHtml(w.originBerthNameSnapshot || 'unknown')}</div>
                ${w.status === 'MATCHED' ? `<div><strong>Match found:</strong> ${escapeHtml(w.matchedBerthNameSnapshot)}</div>` : '<div>Status: waiting for a fit</div>'}
            </div>
            <div class="rc-actions">
                ${w.status === 'MATCHED' ? `<button data-confirm-wait="${w.id}">Confirm &amp; book</button>` : ''}
                <button class="secondary" data-cancel-wait="${w.id}">Remove from waitlist</button>
            </div>
        </div>`).join('');

    list.querySelectorAll('[data-confirm-wait]').forEach(btn => btn.addEventListener('click', async () => {
        try {
            await Api.post('/api/waitlist/' + btn.dataset.confirmWait + '/confirm');
            toast('Booked from waitlist', 'success');
            loadAll();
        } catch (e) { toast('Failed: ' + e.message, 'error'); }
    }));
    list.querySelectorAll('[data-cancel-wait]').forEach(btn => btn.addEventListener('click', async () => {
        if (!confirmDestructive('Remove this vessel from the waitlist?')) return;
        try {
            await Api.post('/api/waitlist/' + btn.dataset.cancelWait + '/cancel');
            toast('Removed from waitlist', 'success');
            loadAll();
        } catch (e) { toast('Failed: ' + e.message, 'error'); }
    }));
}

document.getElementById('checkWaitlistBtn').addEventListener('click', async () => {
    await Api.post('/api/waitlist/check');
    toast('Waitlist checked', 'success');
    loadAll();
});

// ---------- Notifications log ----------

async function renderNotifications() {
    const notifications = await Api.get('/api/notifications');
    const list = document.getElementById('notificationsList');
    if (notifications.length === 0) {
        list.innerHTML = '<p class="empty-note">No notifications yet.</p>';
        return;
    }
    list.innerHTML = notifications.slice(0, 30).map(n => `
        <div class="resolve-card ${n.acknowledged ? '' : 'warn'}" data-id="${n.id}">
            <div class="rc-title">${escapeHtml(n.vesselNameSnapshot || '(no vessel)')} <span class="pill">${n.type}</span></div>
            <div class="rc-meta"><div>${escapeHtml(n.message)}</div><div>${new Date(n.createdAt).toLocaleString()}</div></div>
            ${n.acknowledged
                ? `<div class="rc-meta">Acknowledged${n.outcome ? ': ' + escapeHtml(n.outcome) : ''}</div>`
                : `<div class="rc-actions"><button class="secondary" data-ack="${n.id}">Mark as contacted</button></div>`}
        </div>`).join('');

    list.querySelectorAll('[data-ack]').forEach(btn => btn.addEventListener('click', async () => {
        const outcome = window.prompt('Optional note on the outcome (e.g. "declined, stays waitlisted"):', '') || '';
        try {
            await Api.post('/api/notifications/' + btn.dataset.ack + '/acknowledge?outcome=' + encodeURIComponent(outcome));
            loadAll();
        } catch (e) { toast('Failed: ' + e.message, 'error'); }
    }));
}

loadAll();
