import {apiDelete, apiGet, apiPost, apiPut} from './api.js';
import {debounce, escapeHtml, initInfiniteList} from './common.js';

const list = document.getElementById('list');
const sentinel = document.getElementById('sentinel');
const searchInput = document.getElementById('search');
const empty = document.getElementById('empty');
const dialog = document.getElementById('productDialog');
const form = document.getElementById('productForm');
const dialogTitle = document.getElementById('dialogTitle');
const formError = document.getElementById('formError');

const fId = document.getElementById('productId');
const fName = document.getElementById('fName');
const fCategory = document.getElementById('fCategory');
const fUnit = document.getElementById('fUnit');
const fCalories = document.getElementById('fCalories');
const fProtein = document.getElementById('fProtein');
const fFat = document.getElementById('fFat');
const fCarbs = document.getElementById('fCarbs');

const PAGE_SIZE = 20;

function renderProduct(p) {
    const row = document.createElement('div');
    row.className = 'list-item';
    row.innerHTML = `
    <div class="list-item-main">
      <strong>${escapeHtml(p.name)}</strong>
      <span class="muted">${escapeHtml(p.category || '')}</span>
    </div>
    <div class="list-item-meta">
      ${p.caloriesPer100} kcal / 100${p.unit === 'GRAM' ? 'g' : 'ml'} · P${p.proteinPer100} F${p.fatPer100} C${p.carbsPer100}
    </div>
    <div class="list-item-actions">
      <button class="btn btn-small" data-action="edit">Edit</button>
      <button class="btn btn-small btn-danger" data-action="archive">Archive</button>
    </div>`;
    row.querySelector('[data-action="edit"]').addEventListener('click', () => openEdit(p));
    row.querySelector('[data-action="archive"]').addEventListener('click', () => archiveProduct(p.id, row));
    return row;
}

const listController = initInfiniteList({
    container: list, sentinel, pageSize: PAGE_SIZE,
    fetchPage: async (query, offset, limit) => {
        const params = new URLSearchParams({ query, offset, limit });
        const data = await apiGet(`/products?${params}`);
        empty.textContent = 'Nothing found';
        empty.classList.toggle('hidden', offset > 0 || data.items.length > 0);
        return data.items;
    },
    renderItem: renderProduct,
    onError: (err) => {
        empty.textContent = 'Load error: ' + err.message;
        empty.classList.remove('hidden');
    }
});

searchInput.addEventListener('input', debounce(() => listController.reset(searchInput.value.trim()), 300));

async function loadCategories() {
    const categories = await apiGet('/products/categories');
    document.getElementById('categoryList').innerHTML =
        categories.map(c => `<option value="${escapeHtml(c)}">`).join('');
}
loadCategories();

document.getElementById('btnNew').addEventListener('click', () => {
    fId.value = '';
    form.reset();
    dialogTitle.textContent = 'New product';
    formError.classList.add('hidden');
    dialog.showModal();
});
document.getElementById('btnCancel').addEventListener('click', () => dialog.close());

function openEdit(p) {
    fId.value = p.id;
    fName.value = p.name;
    fCategory.value = p.category || '';
    fUnit.value = p.unit;
    fCalories.value = p.caloriesPer100;
    fProtein.value = p.proteinPer100;
    fFat.value = p.fatPer100;
    fCarbs.value = p.carbsPer100;
    dialogTitle.textContent = 'Edit product';
    formError.classList.add('hidden');
    dialog.showModal();
}

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const payload = {
        name: fName.value.trim(),
        category: fCategory.value.trim() || null,
        unit: fUnit.value,
        caloriesPer100: Number(fCalories.value),
        proteinPer100: Number(fProtein.value),
        fatPer100: Number(fFat.value),
        carbsPer100: Number(fCarbs.value)
    };
    try {
        if (fId.value) await apiPut(`/products/${fId.value}`, payload);
        else await apiPost('/products', payload);
        dialog.close();
        loadCategories();
        listController.reset(searchInput.value.trim());
    } catch (err) {
        formError.textContent = err.message;
        formError.classList.remove('hidden');
    }
});

async function archiveProduct(id, row) {
    if (!confirm('Archive product? It will no longer appear in search.')) return;
    try {
        await apiDelete(`/products/${id}`);
        row.remove();
    } catch (err) {
        alert(err.message);
    }
}