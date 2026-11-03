import { create } from 'zustand';

/** Short confirmations ("Boards saved") shown in the corner for a few seconds. */

interface Toast {
  id: number;
  text: string;
}

interface ToastStore {
  toasts: Toast[];
}

export const useToasts = create<ToastStore>()(() => ({ toasts: [] }));

let nextId = 0;

export function toast(text: string): void {
  const id = ++nextId;
  useToasts.setState((s) => ({ toasts: [...s.toasts.slice(-3), { id, text }] }));
  setTimeout(() => useToasts.setState((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), 3200);
}
