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
 *  toggle set on the Resolve tab and read by the Dashboard and Import Review pages. */
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
