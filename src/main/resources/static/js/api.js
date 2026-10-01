const Api = (() => {
    const currentUser = () => localStorage.getItem('marina.user') || 'staff';

    async function request(method, path, body, extraHeaders) {
        const headers = { 'Content-Type': 'application/json', 'X-User': currentUser() };
        Object.assign(headers, extraHeaders || {});
        const res = await fetch(path, {
            method,
            headers,
            body: body !== undefined ? JSON.stringify(body) : undefined,
        });
        if (!res.ok) {
            let message = res.statusText;
            try {
                const errBody = await res.json();
                message = errBody.message || message;
            } catch (e) { /* ignore */ }
            throw new Error(message);
        }
        if (res.status === 204) return null;
        const text = await res.text();
        return text ? JSON.parse(text) : null;
    }

    return {
        get: (path) => request('GET', path),
        post: (path, body, extraHeaders) => request('POST', path, body, extraHeaders),
        put: (path, body) => request('PUT', path, body),
        del: (path) => request('DELETE', path),
        currentUser,
        setUser: (name) => localStorage.setItem('marina.user', name),
    };
})();

/** Shared site-wide preferences, stored per-browser. Currently just the "hide historical items"
 *  toggle set on the Resolve tab and read by the Stats and Review pages. */
const Settings = (() => {
    const KEY = 'marina.hideHistorical';
    function getHideHistorical() {
        try {
            return localStorage.getItem(KEY) === 'true';
        } catch (e) {
            return false;
        }
    }
    function setHideHistorical(value) {
        try {
            localStorage.setItem(KEY, value ? 'true' : 'false');
        } catch (e) { /* ignore — per-viewer convenience only */ }
    }
    return { getHideHistorical, setHideHistorical };
})();

// ---------- Loading overlay ----------
// Created immediately (before any page-specific JS runs) so it covers the page before any
// data-dependent content can flash an empty/misleading state. Each page calls whenReady(callback)
// instead of fetching its data directly, so nothing renders until the backend confirms it's real.
(function () {
    const overlay = document.createElement('div');
    overlay.id = 'loadingOverlay';
    overlay.className = 'loading-overlay';
    overlay.innerHTML = `
        <div class="loading-spinner"></div>
        <div class="loading-title">Loading marina data…</div>
        <div class="loading-detail" id="loadingDetail">Connecting…</div>
        <div class="loading-note">First load after the site has been idle can take a minute or two —
            the free hosting tier spins down and has to wake up, then re-import 23 years of
            schedule history from scratch.</div>
        <div class="loading-slow" id="loadingSlowNote" style="display:none">Still working on it —
            no need to refresh, it'll come through.</div>`;
    document.addEventListener('DOMContentLoaded', () => document.body.appendChild(overlay));
    if (document.body) document.body.appendChild(overlay);
})();

function phaseMessage(s) {
    switch (s.phase) {
        case 'STARTING': return 'Waking up the server…';
        case 'IMPORTING': return s.message || 'Importing 23 years of schedule history…';
        case 'VALIDATING': return s.totalBerths
            ? `Checking schedule for conflicts (${s.berthsValidated}/${s.totalBerths} berths)…`
            : 'Checking schedule for conflicts…';
        case 'READY': return 'Ready.';
        default: return s.message || 'Loading…';
    }
}

function whenReady(callback) {
    const start = Date.now();
    poll();

    async function poll() {
        let status = null;
        try {
            status = await Api.get('/api/status');
        } catch (e) {
            // Server not answering yet (still waking up) — keep waiting, this isn't an error state.
        }
        const detail = document.getElementById('loadingDetail');
        if (detail) detail.textContent = status ? phaseMessage(status) : 'Waking up the server…';
        const slowNote = document.getElementById('loadingSlowNote');
        if (slowNote) slowNote.style.display = (Date.now() - start > 20000) ? '' : 'none';

        if (status && status.ready) {
            const overlay = document.getElementById('loadingOverlay');
            if (overlay) {
                overlay.classList.add('hide');
                setTimeout(() => overlay.remove(), 400);
            }
            callback();
            return;
        }
        setTimeout(poll, 1200);
    }
}

function newIdempotencyKey() {
    return 'idem-' + Date.now() + '-' + Math.random().toString(36).slice(2);
}

function toast(message, type) {
    let stack = document.querySelector('.toast-stack');
    if (!stack) {
        stack = document.createElement('div');
        stack.className = 'toast-stack';
        document.body.appendChild(stack);
    }
    const el = document.createElement('div');
    el.className = 'toast' + (type ? ' ' + type : '');
    el.textContent = message;
    stack.appendChild(el);
    setTimeout(() => el.remove(), 5000);
}

function confirmDestructive(message) {
    return window.confirm(message);
}

function fmtDate(d) {
    if (!d) return '';
    return d;
}

function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
