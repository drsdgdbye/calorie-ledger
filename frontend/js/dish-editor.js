import {apiDelete, apiGet, apiPost, apiPut} from './api.js';
import {attachProductAutocomplete} from './common.js';

const params = new URLSearchParams(location.search);
const dishId = params.get('id');

const pageTitle = document.getElementById('pageTitle');
const dishName = document.getElementById('dishName');
const cookedWeight = document.getElementById('cookedWeight');
const ingredientsContainer = document.getElementById('ingredients');
const totalsEl = document.getElementById('totals');
const portionGrams = document.getElementById('portionGrams');
const portionResult = document.getElementById('portionResult');
const formError = document.getElementById('formError');
const btnDelete = document.getElementById('btnDelete');
const template = document.getElementById('ingredientRowTemplate');

let rows = [];

function createRow(existing) {
    const fragment = template.content.cloneNode(true);
    const rowEl = fragment.querySelector('.ingredient-row');
    const searchInput = rowEl.querySelector('.ingredient-search');
    const dropdown = rowEl.querySelector('.autocomplete-dropdown');
    const qtyInput = rowEl.querySelector('.ingredient-qty');
    const caloriesLabel = rowEl.querySelector('.ingredient-calories');
    const removeBtn = rowEl.querySelector('.ingredient-remove');

    const rowState = { rowEl, product: null, qtyInput, caloriesLabel };

    attachProductAutocomplete(searchInput, dropdown, (product) => {
        rowState.product = product;
        searchInput.value = product.name;
        qtyInput.placeholder = product.unit === 'GRAM' ? 'g' : 'ml';
        recalculate();
    });

    qtyInput.addEventListener('input', recalculate);
    removeBtn.addEventListener('click', () => {
        rowEl.remove();
        rows = rows.filter(r => r !== rowState);
        recalculate();
    });

    if (existing) {
        rowState.product = {
            id: existing.productId, name: existing.productName, unit: existing.unit,
            caloriesPer100: existing.caloriesPer100, proteinPer100: existing.proteinPer100,
            fatPer100: existing.fatPer100, carbsPer100: existing.carbsPer100
        };
        searchInput.value = existing.productName;
        qtyInput.value = existing.quantity;
    }

    rows.push(rowState);
    ingredientsContainer.appendChild(rowEl);
    return rowState;
}

document.getElementById('btnAddIngredient').addEventListener('click', () => createRow());

function recalculate() {
    let totals = { calories: 0, protein: 0, fat: 0, carbs: 0 };
    rows.forEach(r => {
        const qty = Number(r.qtyInput.value) || 0;
        if (r.product && qty > 0) {
            const factor = qty / 100;
            const cal = r.product.caloriesPer100 * factor;
            totals.calories += cal;
            totals.protein += r.product.proteinPer100 * factor;
            totals.fat += r.product.fatPer100 * factor;
            totals.carbs += r.product.carbsPer100 * factor;
            r.caloriesLabel.textContent = `${Math.round(cal)} kcal`;
        } else {
            r.caloriesLabel.textContent = '—';
        }
    });

    const weight = Number(cookedWeight.value) || 0;
    const per100 = weight > 0 ? {
        calories: Math.round(totals.calories * 100 / weight),
        protein: Math.round(totals.protein * 100 / weight),
        fat: Math.round(totals.fat * 100 / weight),
        carbs: Math.round(totals.carbs * 100 / weight)
    } : null;

    totalsEl.innerHTML = `
    <div><span>Total calories</span><strong>${Math.round(totals.calories)}</strong></div>
    <div><span>Total P/F/C</span><strong>${Math.round(totals.protein)} / ${Math.round(totals.fat)} / ${Math.round(totals.carbs)}</strong></div>
    <div><span>Per 100 g</span><strong>${per100 ? per100.calories + ' kcal' : '—'}</strong></div>
    <div><span>P/F/C per 100 g</span><strong>${per100 ? `${per100.protein} / ${per100.fat} / ${per100.carbs}` : '—'}</strong></div>`;

    const grams = Number(portionGrams.value) || 0;
    portionResult.textContent = (per100 && grams > 0)
        ? `${grams} g = ${Math.round(per100.calories * grams / 100)} kcal`
        : '';
}

cookedWeight.addEventListener('input', recalculate);
portionGrams.addEventListener('input', recalculate);

async function loadExisting() {
    if (!dishId) return;
    pageTitle.textContent = 'Editing dish';
    btnDelete.classList.remove('hidden');
    try {
        const dish = await apiGet(`/dishes/${dishId}`);
        dishName.value = dish.name;
        cookedWeight.value = dish.cookedWeightGrams;
        dish.ingredients.forEach(ing => createRow(ing));
        recalculate();
    } catch (err) {
        showError(err.message);
    }
}
loadExisting();

document.getElementById('btnSave').addEventListener('click', async () => {
    formError.classList.add('hidden');
    const ingredients = rows
        .filter(r => r.product && Number(r.qtyInput.value) > 0)
        .map(r => ({ productId: r.product.id, quantity: Number(r.qtyInput.value) }));

    if (!dishName.value.trim()) return showError('Please provide a dish name');
    if (!cookedWeight.value || Number(cookedWeight.value) <= 0) return showError('Please provide the cooked weight');
    if (ingredients.length === 0) return showError('Add at least one ingredient');

    const payload = {
        name: dishName.value.trim(),
        cookedWeightGrams: Number(cookedWeight.value),
        ingredients
    };

    try {
        const saved = dishId ? await apiPut(`/dishes/${dishId}`, payload) : await apiPost('/dishes', payload);
        location.href = `/dish-editor.html?id=${saved.id}`;
    } catch (err) {
        showError(err.message);
    }
});

btnDelete.addEventListener('click', async () => {
    if (!confirm('Delete dish permanently?')) return;
    try {
        await apiDelete(`/dishes/${dishId}`);
        location.href = '/index.html';
    } catch (err) {
        showError(err.message);
    }
});

function showError(message) {
    formError.textContent = message;
    formError.classList.remove('hidden');
}