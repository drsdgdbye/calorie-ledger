const API_BASE = '/api';

async function toError(res) {
    let message = `Error ${res.status}`;
    try { const data = await res.json(); if (data.error) message = data.error; } catch {}
    return new Error(message);
}

export async function apiGet(path) {
    const res = await fetch(API_BASE + path);
    if (!res.ok) throw await toError(res);
    return res.status === 204 ? null : res.json();
}

export async function apiPost(path, body) {
    const res = await fetch(API_BASE + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    if (!res.ok) throw await toError(res);
    return res.json();
}

export async function apiPut(path, body) {
    const res = await fetch(API_BASE + path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    if (!res.ok) throw await toError(res);
    return res.json();
}

export async function apiDelete(path) {
    const res = await fetch(API_BASE + path, { method: 'DELETE' });
    if (!res.ok) throw await toError(res);
}