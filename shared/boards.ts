/**
 * Board themes: royal looks for the Ludo board, bought with diamonds in the shop. The
 * room plays on its host's board, so everyone at the table sees it. The server owns the
 * catalogue and prices (an administrator can change them); clients draw each board by its
 * id and fall back to the classic board for an id they don't know.
 */
export interface BoardTheme {
  id: string;
  name: string;
  /** Default price in diamonds (0 = everyone has it). */
  price: number;
  /** One line for the shop. */
  blurb: string;
  /** Shown with a crown ribbon in the shop. */
  royal: boolean;
}

export const DEFAULT_BOARD = 'classic';

export const BOARDS: BoardTheme[] = [
  { id: 'classic', name: 'Classic', price: 0, blurb: 'The wooden board everyone knows.', royal: false },
  { id: 'royal_gold', name: 'Royal Gold', price: 200, blurb: 'Ivory and gold, fit for a king.', royal: true },
  { id: 'maharaja', name: 'Maharaja', price: 250, blurb: 'Crimson silk and a golden palace rim.', royal: true },
  { id: 'emerald_palace', name: 'Emerald Palace', price: 250, blurb: 'Jade halls trimmed with gold.', royal: true },
  { id: 'sapphire_throne', name: 'Sapphire Throne', price: 300, blurb: 'Deep blue velvet and silver.', royal: true },
  { id: 'black_marble', name: 'Black Marble Empire', price: 400, blurb: 'Night-black marble, blazing gold.', royal: true },
];

export function boardTheme(id: string | null | undefined): BoardTheme | undefined {
  return BOARDS.find((b) => b.id === id);
}
