import {apiGet} from './api.js';
import {debounce, escapeHtml, initInfiniteList} from './common.js';

const list = document.getElementById('list');
const sentinel = document.getElementById('sentinel');
const searchInput = document.getElementById('search');
const empty = document.getElementById('empty');
const PAGE_SIZE = 20;

function renderDish(d) {
    const row = document.createElement('a');
    row.className = 'list-item list-item-link';
    row.href = `/dish-editor.html?id=${d.id}`;
    row.innerHTML = `
    <div class="list-item-main"><strong>${escapeHtml(d.name)}</strong></div>
    <div class="list-item-meta">${d.caloriesPer100} kcal / 100g · dish weight ${d.cookedWeightGrams} g</div>`;
    return row;
}

const listController = initInfiniteList({
    container: list, sentinel, pageSize: PAGE_SIZE,
    fetchPage: async (query, offset, limit) => {
        const params = new URLSearchParams({ query, offset, limit });
        const data = await apiGet(`/dishes?${params}`);
        empty.textContent = 'Nothing found';
        empty.classList.toggle('hidden', offset > 0 || data.items.length > 0);
        return data.items;
    },
    renderItem: renderDish,
    onError: (err) => {
        empty.textContent = 'Load error: ' + err.message;
        empty.classList.remove('hidden');
    }
});

searchInput.addEventListener('input', debounce(() => listController.reset(searchInput.value.trim()), 300));