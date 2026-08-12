import {apiGet} from './api.js';

export function debounce(fn, delay = 300) {
    let timer = null;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), delay);
    };
}

export function initInfiniteList({ container, sentinel, pageSize, fetchPage, renderItem, onError = () => {} }) {
    let offset = 0;
    let query = '';
    let loading = false;
    let exhausted = false;
    let generation = 0;

    async function loadNext() {
        if (loading || exhausted) return;
        loading = true;
        const gen = generation;
        try {
            const items = await fetchPage(query, offset, pageSize);
            if (gen !== generation) return;
            items.forEach(item => {
                const node = renderItem(item);
                if (node) container.appendChild(node);
            });
            offset += items.length;
            if (items.length < pageSize) exhausted = true;
        } catch (err) {
            if (gen !== generation) return;
            onError(err);
        } finally {
            loading = false;
            if (gen !== generation) loadNext();
        }
    }

    function reset(newQuery) {
        generation += 1;
        query = newQuery;
        offset = 0;
        exhausted = false;
        container.innerHTML = '';
        loadNext();
    }

    new IntersectionObserver(entries => {
        if (entries[0].isIntersecting) loadNext();
    }).observe(sentinel);

    reset('');
    return { reset };
}

export function attachProductAutocomplete(input, dropdown, onSelect) {
    let currentItems = [];
    let searchSeq = 0;

    const doSearch = debounce(async () => {
        const query = input.value.trim();
        if (!query) { dropdown.innerHTML = ''; dropdown.classList.add('hidden'); return; }
        const seq = ++searchSeq;
        let data;
        try {
            data = await apiGet(`/products?query=${encodeURIComponent(query)}&limit=8&offset=0`);
        } catch (err) {
            if (seq !== searchSeq || input.value.trim() !== query) return;
            dropdown.innerHTML = '<div class="autocomplete-empty">Load error</div>';
            return;
        }
        if (seq !== searchSeq || input.value.trim() !== query) return;
        currentItems = data.items;
        render();
    }, 250);

    function render() {
        dropdown.innerHTML = '';
        if (currentItems.length === 0) {
            dropdown.innerHTML = '<div class="autocomplete-empty">Not found</div>';
        } else {
            currentItems.forEach(p => {
                const item = document.createElement('div');
                item.className = 'autocomplete-item';
                item.textContent = `${p.name} (${p.caloriesPer100} kcal/100${p.unit === 'GRAM' ? 'g' : 'ml'})`;
                item.addEventListener('mousedown', (e) => {
                    e.preventDefault();
                    onSelect(p);
                    dropdown.innerHTML = '';
                    dropdown.classList.add('hidden');
                });
                dropdown.appendChild(item);
            });
        }
        dropdown.classList.remove('hidden');
    }

    input.addEventListener('input', doSearch);
    input.addEventListener('blur', () => setTimeout(() => dropdown.classList.add('hidden'), 150));
    input.addEventListener('focus', () => { if (currentItems.length) dropdown.classList.remove('hidden'); });
}

export function escapeHtml(s) {
    const div = document.createElement('div');
    div.textContent = s ?? '';
    return div.innerHTML;
}