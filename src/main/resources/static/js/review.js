let allItems = [];

async function loadReview() {
    allItems = await Api.get('/api/import/review-queue');
    const categories = [...new Set(allItems.map(i => i.category))].sort();
    const catSelect = document.getElementById('categoryFilter');
    catSelect.innerHTML = '<option value="">All categories</option>' +
        categories.map(c => `<option value="${c}">${c} (${allItems.filter(i => i.category === c).length})</option>`).join('');
    render();
}

function render() {
    const showResolved = document.getElementById('showResolved').checked;
    const cat = document.getElementById('categoryFilter').value;
    const rows = allItems.filter(i => (showResolved || !i.resolved) && (!cat || i.category === cat));
    document.getElementById('countLabel').textContent = `${rows.length} of ${allItems.length} items`;

    const body = document.querySelector('#reviewTable tbody');
    body.innerHTML = rows.map(i => `
        <tr style="${i.resolved ? 'opacity:0.5' : ''}">
            <td><span class="pill">${escapeHtml(i.category)}</span></td>
            <td>${escapeHtml(i.sheet || '')}</td>
            <td>${escapeHtml(i.location || '')}</td>
            <td class="small">${escapeHtml(i.description)}</td>
            <td class="small muted">${escapeHtml(i.rawData || '')}</td>
            <td>${i.resolved ? '' : `<button class="secondary" data-resolve="${i.id}">Mark reviewed</button>`}</td>
        </tr>`).join('') || '<tr><td colspan="6" class="muted">Nothing here</td></tr>';

    body.querySelectorAll('[data-resolve]').forEach(btn => btn.addEventListener('click', async () => {
        try {
            await Api.post(`/api/import/review-queue/${btn.dataset.resolve}/resolve`);
            await loadReview();
        } catch (e) {
            toast('Failed: ' + e.message, 'error');
        }
    }));
}

document.getElementById('showResolved').addEventListener('change', render);
document.getElementById('categoryFilter').addEventListener('change', render);
loadReview();
