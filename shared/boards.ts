/**
 * Board skins: looks for the Ludo board, bought in the shop with coins or (the rarest) with
 * diamonds. A room plays on its host's paid board or, when the host has only the free one, on
 * the paid board of whoever sat down first (locked when the match starts), so everyone at the
 * table sees it; opponents never need to own it. The server owns the catalogue, prices and publishing (an
 * administrator can change them and add new boards). Every board keeps the same 15x15 grid,
 * paths, safe cells, yards and home: only paint, decoration and lighting change.
 *
 * A board's look is data: the app paints the board face, its rim, the 3D decorations on the
 * corners, the sky behind it and the ambient effect from `look`, so a new board can be made
 * from these parts without an app update. Colours are #rrggbb or #aarrggbb.
 */

/** The ornament in the centre of the board. */
export type BoardEmblem =
  | 'none' | 'crown' | 'palace' | 'throne' | 'diamond' | 'heart' | 'twin_hearts' | 'moon' | 'rose' | 'eiffel' | 'blossom'
  | 'castle' | 'flower' | 'pearl' | 'leaf' | 'waterfall' | 'sun' | 'mountain' | 'aurora' | 'dragon' | 'snowflake' | 'flame'
  | 'planet' | 'crystal' | 'tree' | 'eye' | 'rocket' | 'lx' | 'flag' | 'palm' | 'cupid' | 'lantern';
/** What is worked into the four yards' inner panels (never onto the paths). */
export type BoardPattern =
  | 'none' | 'veins' | 'hearts' | 'quilt' | 'flag' | 'stars' | 'roses' | 'petals' | 'leaves' | 'waves' | 'sunset' | 'sand'
  | 'grid' | 'snow' | 'nebula' | 'hieroglyphs' | 'damask' | 'bubbles' | 'aurora' | 'lights' | 'flames' | 'crystals'
  | 'scales' | 'clouds' | 'vines';
/** The mark on the safe cells. */
export type BoardSafeMark = 'star' | 'heart' | 'diamond' | 'flower' | 'snowflake' | 'flame' | 'moon' | 'pearl' | 'leaf';
/** 3D decorations standing on the board's four corners. */
export type BoardDecor = 'none' | 'pillar' | 'crystal' | 'lantern' | 'orb' | 'tree';
/** A gentle effect drifting over the board in a match (players can turn these off). */
export type BoardAmbient = 'none' | 'hearts' | 'petals' | 'stars' | 'snow' | 'embers' | 'bubbles' | 'leaves' | 'fireflies' | 'sparkles';

export interface BoardLook {
  /** Board face behind everything, the yards' panels (centre and edge), the path cells. */
  base: string;
  panel: string;
  panelEdge: string;
  track: string;
  /** Cell lines, safe-cell marks, painted outer edge, metal trim ('' = a plain board). */
  grid: string;
  safe: string;
  edge: string;
  trim: string;
  emblem: BoardEmblem;
  pattern: BoardPattern;
  safeMark: BoardSafeMark;
  /** The 3D base and raised rim (0..1 RGB) and how shiny the rim is. */
  wood: [number, number, number];
  rim: [number, number, number];
  rimSpec: number;
  /** The colour around the board in a match (its environment). */
  sky: string;
  decor: BoardDecor;
  decorColor: string;
  ambient: BoardAmbient;
}

/** What a board is paid with: coins from the wallet or diamonds. */
export type BoardCurrency = 'coins' | 'gems';
export type BoardRarity = 'common' | 'rare' | 'epic' | 'legendary' | 'mythic' | 'ultimate';
export type BoardCollection =
  | 'royal' | 'luxury' | 'couple' | 'love' | 'nature' | 'fantasy' | 'neon' | 'seasonal' | 'exclusive' | 'free';

export interface BoardTheme {
  id: string;
  name: string;
  /** Default price, in `currency` (0 = everyone has it). */
  price: number;
  currency: BoardCurrency;
  /** One line for the shop. */
  blurb: string;
  /** Shown with a crown ribbon in the shop (any premium board). */
  royal: boolean;
  /** A ribbon for the shop (e.g. for couples). */
  badge?: string;
  rarity: BoardRarity;
  collection: BoardCollection;
  /** When it went on sale (YYYY-MM-DD): new for 30 days after. */
  added: string;
  look: BoardLook;
}

export const DEFAULT_BOARD = 'classic';
export const BOARD_COLLECTIONS: BoardCollection[] = ['couple', 'love', 'royal', 'luxury', 'nature', 'fantasy', 'neon', 'seasonal', 'exclusive', 'free'];
export const BOARD_CURRENCIES: BoardCurrency[] = ['coins', 'gems'];
export const BOARD_RARITIES: BoardRarity[] = ['common', 'rare', 'epic', 'legendary', 'mythic', 'ultimate'];
export const BOARD_EMBLEMS: BoardEmblem[] = [
  'none', 'crown', 'palace', 'throne', 'diamond', 'heart', 'twin_hearts', 'moon', 'rose', 'eiffel', 'blossom', 'castle', 'flower',
  'pearl', 'leaf', 'waterfall', 'sun', 'mountain', 'aurora', 'dragon', 'snowflake', 'flame', 'planet', 'crystal', 'tree', 'eye',
  'rocket', 'lx', 'flag', 'palm', 'cupid', 'lantern',
];
export const BOARD_PATTERNS: BoardPattern[] = [
  'none', 'veins', 'hearts', 'quilt', 'flag', 'stars', 'roses', 'petals', 'leaves', 'waves', 'sunset', 'sand', 'grid', 'snow',
  'nebula', 'hieroglyphs', 'damask', 'bubbles', 'aurora', 'lights', 'flames', 'crystals', 'scales', 'clouds', 'vines',
];
export const BOARD_SAFE_MARKS: BoardSafeMark[] = ['star', 'heart', 'diamond', 'flower', 'snowflake', 'flame', 'moon', 'pearl', 'leaf'];
export const BOARD_DECORS: BoardDecor[] = ['none', 'pillar', 'crystal', 'lantern', 'orb', 'tree'];
export const BOARD_AMBIENTS: BoardAmbient[] = ['none', 'hearts', 'petals', 'stars', 'snow', 'embers', 'bubbles', 'leaves', 'fireflies', 'sparkles'];

const OLD = '2026-11-01';
const NEW = '2026-11-03';

type LookIn = Partial<BoardLook> & Pick<BoardLook, 'panel' | 'panelEdge' | 'trim' | 'emblem' | 'pattern' | 'rim' | 'sky'>;

/** A look with sensible defaults for what a theme doesn't set. */
function look(l: LookIn): BoardLook {
  return {
    base: l.base ?? l.panelEdge,
    track: l.track ?? '#fffdf8',
    grid: l.grid ?? '#40000000',
    safe: l.safe ?? l.trim ?? '#b9b2a0',
    edge: l.edge ?? '#3b2a1a',
    safeMark: l.safeMark ?? 'star',
    wood: l.wood ?? [0.4, 0.26, 0.15],
    rimSpec: l.rimSpec ?? 0.9,
    decor: l.decor ?? 'none',
    decorColor: l.decorColor ?? l.trim ?? '#e0b23a',
    ambient: l.ambient ?? 'none',
    ...l,
  } as BoardLook;
}

type Def = Omit<BoardTheme, 'royal' | 'added' | 'currency'> & { added?: string; currency?: BoardCurrency };
const board = (d: Def): BoardTheme => ({ ...d, currency: d.currency ?? 'coins', royal: d.price > 0, added: d.added ?? NEW });

export const BOARDS: BoardTheme[] = [
  // ------------------------------------------------------------------ Free
  board({
    id: 'classic', name: 'Classic', price: 0, rarity: 'common', collection: 'free', added: '2025-01-01', blurb: 'The wooden board everyone knows.',
    look: look({ base: '#f7f1e3', panel: '#fdfaf2', panelEdge: '#fdfaf2', track: '#ffffff', grid: '#40000000', safe: '#b9b2a0', edge: '#3b2a1a', trim: '',
      emblem: 'none', pattern: 'none', rim: [0.33, 0.21, 0.12], rimSpec: 0.18, sky: '#4316a0' }),
  }),

  // ------------------------------------------------------------------ Couple & Romantic
  board({
    id: 'love_story', name: 'Love Story', price: 3500, rarity: 'epic', collection: 'couple', added: OLD, badge: '💞 FOR COUPLES',
    blurb: 'Pink and purple, rose borders and a glowing heart in the middle.',
    look: look({ base: '#f6c6dc', panel: '#fff1f6', panelEdge: '#f7b7d2', track: '#ffeef4', grid: '#40c2185b', safe: '#e91e63', edge: '#880e4f', trim: '#e8a87c',
      emblem: 'heart', pattern: 'hearts', safeMark: 'heart', wood: [0.45, 0.12, 0.32], rim: [0.93, 0.64, 0.56], rimSpec: 1, sky: '#4a1452',
      decor: 'lantern', decorColor: '#ff7aa8', ambient: 'hearts' }),
  }),
  board({
    id: 'moonlight_love', name: 'Moonlight Love', price: 4500, rarity: 'epic', collection: 'couple', badge: '🌙 FOR COUPLES',
    blurb: 'A moonlit night sky, glowing stars and a crescent moon for two.',
    look: look({ base: '#1c1e4a', panel: '#2c2f6e', panelEdge: '#12133a', track: '#eef0ff', grid: '#55303470', safe: '#ffe08a', edge: '#0b0c2a', trim: '#c9d1f0',
      emblem: 'moon', pattern: 'stars', safeMark: 'moon', wood: [0.06, 0.07, 0.2], rim: [0.78, 0.8, 0.92], rimSpec: 1.1, sky: '#0b0c2a',
      decor: 'orb', decorColor: '#fff3c4', ambient: 'stars' }),
  }),
  board({
    id: 'rose_garden', name: 'Rose Garden', price: 4000, rarity: 'epic', collection: 'couple', badge: '🌹 FOR COUPLES',
    blurb: 'Red and pink roses in a green garden, softly lit.',
    look: look({ base: '#2f6b3a', panel: '#e9f6e6', panelEdge: '#a9d8a0', track: '#fffaf6', grid: '#40205a28', safe: '#d81b47', edge: '#1d4a25', trim: '#e0b23a',
      emblem: 'rose', pattern: 'roses', safeMark: 'flower', wood: [0.16, 0.32, 0.16], rim: [0.86, 0.66, 0.24], sky: '#163a1f',
      decor: 'tree', decorColor: '#e05a7a', ambient: 'petals' }),
  }),
  board({
    id: 'forever_love', name: 'Forever Love', price: 5000, rarity: 'legendary', collection: 'couple', badge: '💍 FOR COUPLES',
    blurb: 'Red velvet and gold with two hearts entwined.',
    look: look({ base: '#7a0f1e', panel: '#fff3e6', panelEdge: '#f2c9a8', track: '#fff8f0', grid: '#55801020', safe: '#c8102e', edge: '#4a0610', trim: '#f0c24a',
      emblem: 'twin_hearts', pattern: 'damask', safeMark: 'heart', wood: [0.38, 0.03, 0.08], rim: [0.95, 0.75, 0.25], rimSpec: 1.1, sky: '#3a0510',
      decor: 'pillar', decorColor: '#f0c24a', ambient: 'hearts' }),
  }),
  board({
    id: 'pink_diamond', name: 'Pink Diamond', price: 550, currency: 'gems', rarity: 'legendary', collection: 'couple', badge: '💗 FOR COUPLES',
    blurb: 'Pink crystal, diamonds and rose-gold rims, softly glowing.',
    look: look({ base: '#f7c8dc', panel: '#fff0f7', panelEdge: '#f5aecd', track: '#fff4f9', grid: '#40c2185b', safe: '#ff5fa2', edge: '#8a2a55', trim: '#eab0a0',
      emblem: 'diamond', pattern: 'crystals', safeMark: 'diamond', wood: [0.6, 0.3, 0.42], rim: [0.95, 0.7, 0.66], rimSpec: 1.2, sky: '#5a1d44',
      decor: 'crystal', decorColor: '#ff9cc8', ambient: 'sparkles' }),
  }),
  board({
    id: 'sunset_date', name: 'Sunset Date', price: 3000, rarity: 'rare', collection: 'couple', badge: '🌅 FOR COUPLES',
    blurb: 'Orange, pink and purple skies of a warm evening together.',
    look: look({ base: '#f08a5d', panel: '#ffd9a8', panelEdge: '#c45a8a', track: '#fff6ee', grid: '#40803020', safe: '#ff6a3d', edge: '#5a1f4a', trim: '#ffcf7a',
      emblem: 'sun', pattern: 'sunset', safeMark: 'heart', wood: [0.4, 0.14, 0.3], rim: [0.98, 0.72, 0.42], sky: '#5a1f4a',
      decor: 'lantern', decorColor: '#ffb347', ambient: 'fireflies' }),
  }),
  board({
    id: 'paris_romance', name: 'Paris Romance', price: 600, currency: 'gems', rarity: 'legendary', collection: 'couple', badge: '🗼 FOR COUPLES',
    blurb: 'Eiffel Tower evenings, street lamps and French romance.',
    look: look({ base: '#2a2f55', panel: '#f3ecff', panelEdge: '#c3b6e6', track: '#fbf8ff', grid: '#40303070', safe: '#e05a7a', edge: '#151938', trim: '#d9b968',
      emblem: 'eiffel', pattern: 'lights', safeMark: 'heart', wood: [0.12, 0.13, 0.26], rim: [0.85, 0.72, 0.42], sky: '#151938',
      decor: 'lantern', decorColor: '#ffd98a', ambient: 'fireflies' }),
  }),
  board({
    id: 'cherry_blossom', name: 'Cherry Blossom', price: 4500, rarity: 'epic', collection: 'couple', badge: '🌸 FOR COUPLES',
    blurb: 'Pink and white blossoms with petals drifting by.',
    look: look({ base: '#f7dbe6', panel: '#fff7fa', panelEdge: '#f6c4d6', track: '#ffffff', grid: '#40a0607a', safe: '#ff8fb3', edge: '#6a3a4a', trim: '#c9a0a8',
      emblem: 'blossom', pattern: 'petals', safeMark: 'flower', wood: [0.38, 0.24, 0.24], rim: [0.86, 0.72, 0.74], sky: '#5a2e44',
      decor: 'tree', decorColor: '#ffb7cf', ambient: 'petals' }),
  }),
  board({
    id: 'heart_kingdom', name: 'Heart Kingdom', price: 650, currency: 'gems', rarity: 'mythic', collection: 'couple', badge: '🏰 FOR COUPLES',
    blurb: 'A fairy-tale castle of pink and gold around a glowing heart.',
    look: look({ base: '#e9a3c9', panel: '#fff0f8', panelEdge: '#e8a8cc', track: '#fff7fb', grid: '#409a3a70', safe: '#ff3d8b', edge: '#6a1a4a', trim: '#f2c75a',
      emblem: 'castle', pattern: 'hearts', safeMark: 'heart', wood: [0.5, 0.16, 0.36], rim: [0.98, 0.78, 0.32], rimSpec: 1.2, sky: '#3d0f33',
      decor: 'pillar', decorColor: '#ff8fc0', ambient: 'hearts' }),
  }),
  board({
    id: 'our_little_world', name: 'Our Little World', price: 4000, rarity: 'epic', collection: 'couple', badge: '✨ FOR COUPLES',
    blurb: 'A dreamy little garden under floating stars, warm and cosy.',
    look: look({ base: '#3a5a7a', panel: '#fdf6e3', panelEdge: '#bfe0c4', track: '#fffbef', grid: '#40304a5a', safe: '#ffb347', edge: '#1d3348', trim: '#ffd27a',
      emblem: 'flower', pattern: 'clouds', safeMark: 'star', wood: [0.3, 0.22, 0.14], rim: [0.98, 0.82, 0.48], sky: '#1d3348',
      decor: 'tree', decorColor: '#9ad48a', ambient: 'fireflies' }),
  }),

  // ------------------------------------------------------------------ Love & Valentine
  board({
    id: 'valentine', name: 'Valentine Night', price: 3000, rarity: 'rare', collection: 'love', badge: '💌 VALENTINE',
    blurb: 'Red and white hearts and a love letter in the middle.',
    look: look({ base: '#c8102e', panel: '#ffffff', panelEdge: '#ffd6dd', track: '#fff5f6', grid: '#40a00020', safe: '#e0115f', edge: '#6a0010', trim: '#f2d0d6',
      emblem: 'heart', pattern: 'hearts', safeMark: 'heart', wood: [0.5, 0.04, 0.1], rim: [0.95, 0.85, 0.87], sky: '#4a0010', decor: 'orb', decorColor: '#ff4f7b', ambient: 'hearts' }),
  }),
  board({
    id: 'cupid', name: "Cupid's Arrow", price: 3500, rarity: 'epic', collection: 'love', badge: '💘 VALENTINE',
    blurb: 'A golden arrow through two hearts, clouds and soft light.',
    look: look({ base: '#f3b8d0', panel: '#fff5fa', panelEdge: '#cfe3ff', track: '#ffffff', grid: '#40806080', safe: '#ff4f8a', edge: '#5a2a6a', trim: '#f0c24a',
      emblem: 'cupid', pattern: 'clouds', safeMark: 'heart', wood: [0.5, 0.3, 0.5], rim: [0.98, 0.8, 0.36], sky: '#3a1a4a', decor: 'orb', decorColor: '#fff3c4', ambient: 'hearts' }),
  }),

  // ------------------------------------------------------------------ Royal
  board({
    id: 'royal_gold', name: 'Royal Gold', price: 5000, rarity: 'legendary', collection: 'royal', added: OLD,
    blurb: 'Gold rims, royal crowns and rich golden home zones.',
    look: look({ base: '#f3dca0', panel: '#fff8e4', panelEdge: '#ecd08a', track: '#fffaeb', grid: '#55a07a1a', safe: '#d4a017', edge: '#8a5a00', trim: '#e0b23a',
      emblem: 'crown', pattern: 'damask', wood: [0.42, 0.08, 0.1], rim: [0.92, 0.72, 0.22], rimSpec: 1, sky: '#3a1a06', decor: 'pillar', decorColor: '#e0b23a', ambient: 'sparkles' }),
  }),
  board({
    id: 'royal_palace', name: 'Royal Palace', price: 750, currency: 'gems', rarity: 'mythic', collection: 'royal',
    blurb: 'Golden pillars, red velvet and the light of a grand palace.',
    look: look({ base: '#8a0f22', panel: '#a8172e', panelEdge: '#5a0612', track: '#fff6e6', grid: '#55600010', safe: '#f0c24a', edge: '#3a0208', trim: '#f0c24a',
      emblem: 'palace', pattern: 'damask', wood: [0.36, 0.03, 0.07], rim: [0.96, 0.76, 0.26], rimSpec: 1.2, sky: '#2a0206', decor: 'pillar', decorColor: '#f0c24a', ambient: 'sparkles' }),
  }),
  board({
    id: 'black_marble', name: 'Black Royal', price: 6000, rarity: 'legendary', collection: 'royal', added: OLD,
    blurb: 'Black marble and gold ornaments in a dark, rich hall.',
    look: look({ base: '#141318', panel: '#3a3642', panelEdge: '#121116', track: '#f3efe6', grid: '#66000000', safe: '#d4a017', edge: '#000000', trim: '#e8b923',
      emblem: 'crown', pattern: 'veins', wood: [0.06, 0.06, 0.07], rim: [0.92, 0.72, 0.22], rimSpec: 1.1, sky: '#08070a', decor: 'pillar', decorColor: '#e8b923' }),
  }),
  board({
    id: 'emperor_kingdom', name: 'Emperor Kingdom', price: 700, currency: 'gems', rarity: 'mythic', collection: 'royal',
    blurb: 'An ancient kingdom with a throne at its heart.',
    look: look({ base: '#5a3a1a', panel: '#f6e6c4', panelEdge: '#c9a46a', track: '#fff8ea', grid: '#55603a10', safe: '#a8323a', edge: '#2a1808', trim: '#d9a83a',
      emblem: 'throne', pattern: 'scales', wood: [0.3, 0.17, 0.07], rim: [0.86, 0.64, 0.22], sky: '#1f1206', decor: 'pillar', decorColor: '#d9a83a', ambient: 'embers' }),
  }),
  board({
    id: 'royal_diamond', name: 'Royal Diamond', price: 650, currency: 'gems', rarity: 'legendary', collection: 'royal',
    blurb: 'Crystal and diamonds with gold and blue accents.',
    look: look({ base: '#c9e8ff', panel: '#f4fbff', panelEdge: '#b4dcf5', track: '#ffffff', grid: '#40305a8a', safe: '#1e88e5', edge: '#0d2a6e', trim: '#e0b23a',
      emblem: 'diamond', pattern: 'crystals', safeMark: 'diamond', wood: [0.1, 0.18, 0.4], rim: [0.92, 0.74, 0.26], rimSpec: 1.2, sky: '#0d1a3a', decor: 'crystal', decorColor: '#9adfff', ambient: 'sparkles' }),
  }),

  // ------------------------------------------------------------------ Luxury
  board({
    id: 'billionaire', name: 'Billionaire', price: 900, currency: 'gems', rarity: 'mythic', collection: 'luxury', added: OLD, badge: '💎 VIP',
    blurb: "Black leather, solid gold and diamonds. Show them who's boss.",
    look: look({ base: '#0b0b0d', panel: '#26221a', panelEdge: '#0a0906', track: '#fff6dc', grid: '#77b8860b', safe: '#7fe3ff', edge: '#000000', trim: '#ffcf40',
      emblem: 'diamond', pattern: 'quilt', safeMark: 'diamond', wood: [0.05, 0.05, 0.05], rim: [1, 0.8, 0.26], rimSpec: 1.2, sky: '#050505', decor: 'crystal', decorColor: '#ffcf40', ambient: 'sparkles' }),
  }),
  board({
    id: 'maharaja', name: 'Maharaja', price: 5500, rarity: 'legendary', collection: 'luxury', added: OLD,
    blurb: 'Crimson silk and a golden palace rim.',
    look: look({ base: '#f2b8a0', panel: '#fde9dc', panelEdge: '#eeac92', track: '#fff3e8', grid: '#55801020', safe: '#c8102e', edge: '#6a0816', trim: '#e8b84a',
      emblem: 'crown', pattern: 'damask', wood: [0.45, 0.05, 0.08], rim: [0.66, 0.07, 0.13], rimSpec: 0.7, sky: '#3a0410', decor: 'lantern', decorColor: '#ffcf7a' }),
  }),
  board({
    id: 'emerald_palace', name: 'Emerald Palace', price: 5500, rarity: 'legendary', collection: 'luxury', added: OLD,
    blurb: 'Jade halls trimmed with gold.',
    look: look({ base: '#bfe6cc', panel: '#ecf9f1', panelEdge: '#aedcbe', track: '#f7fff9', grid: '#55105a30', safe: '#0f8a4e', edge: '#0a4f2d', trim: '#dcb64a',
      emblem: 'crown', pattern: 'veins', wood: [0.04, 0.25, 0.14], rim: [0.86, 0.67, 0.22], sky: '#04261a', decor: 'pillar', decorColor: '#dcb64a' }),
  }),
  board({
    id: 'sapphire_throne', name: 'Sapphire Throne', price: 6000, rarity: 'legendary', collection: 'luxury', added: OLD,
    blurb: 'Deep blue velvet and silver.',
    look: look({ base: '#bccdf5', panel: '#ecf1ff', panelEdge: '#aebff0', track: '#f6f8ff', grid: '#55102a6e', safe: '#1a4fc4', edge: '#0d2a6e', trim: '#d3dae6',
      emblem: 'throne', pattern: 'damask', wood: [0.05, 0.1, 0.3], rim: [0.8, 0.83, 0.9], rimSpec: 1.1, sky: '#08143a', decor: 'crystal', decorColor: '#cfe0ff' }),
  }),

  // ------------------------------------------------------------------ Nature
  board({
    id: 'tropical_paradise', name: 'Tropical Paradise', price: 4000, rarity: 'epic', collection: 'nature',
    blurb: 'Palm leaves, turquoise water and warm island sun.',
    look: look({ base: '#2bb5a0', panel: '#f2fbef', panelEdge: '#9fe0c4', track: '#fffdf2', grid: '#40205a4a', safe: '#ff8a3d', edge: '#0f5a4a', trim: '#f2c95a',
      emblem: 'palm', pattern: 'leaves', safeMark: 'flower', wood: [0.42, 0.3, 0.16], rim: [0.55, 0.4, 0.2], rimSpec: 0.4, sky: '#0a4a44', decor: 'tree', decorColor: '#3ab06a', ambient: 'leaves' }),
  }),
  board({
    id: 'green_forest', name: 'Green Forest', price: 3000, rarity: 'rare', collection: 'nature',
    blurb: 'Moss, ferns and sunlight through tall trees.',
    look: look({ base: '#2f5a2a', panel: '#e6f2d8', panelEdge: '#9cc48a', track: '#fbfdf4', grid: '#40203a10', safe: '#5a8a2a', edge: '#1a3314', trim: '#b8a06a',
      emblem: 'tree', pattern: 'leaves', safeMark: 'leaf', wood: [0.3, 0.2, 0.1], rim: [0.36, 0.24, 0.12], rimSpec: 0.3, sky: '#12260f', decor: 'tree', decorColor: '#4a8a3a', ambient: 'fireflies' }),
  }),
  board({
    id: 'waterfall_kingdom', name: 'Waterfall Kingdom', price: 5000, rarity: 'legendary', collection: 'nature',
    blurb: 'Cascading water, misty cliffs and green stone.',
    look: look({ base: '#3a7a8a', panel: '#e8f7fb', panelEdge: '#9cd3e0', track: '#fbffff', grid: '#40204a5a', safe: '#2a9ad4', edge: '#163a44', trim: '#a9c8c0',
      emblem: 'waterfall', pattern: 'waves', safeMark: 'pearl', wood: [0.22, 0.28, 0.24], rim: [0.6, 0.72, 0.7], rimSpec: 0.6, sky: '#0f2a33', decor: 'orb', decorColor: '#bff0ff', ambient: 'bubbles' }),
  }),
  board({
    id: 'ocean_pearl', name: 'Ocean Pearl', price: 4500, rarity: 'epic', collection: 'nature',
    blurb: 'Deep blue sea with a shining pearl at its heart.',
    look: look({ base: '#0f5a8a', panel: '#e6f4ff', panelEdge: '#7fc4ea', track: '#f6fcff', grid: '#40104a7a', safe: '#ffffff', edge: '#062a44', trim: '#e6e0d6',
      emblem: 'pearl', pattern: 'waves', safeMark: 'pearl', wood: [0.05, 0.2, 0.34], rim: [0.9, 0.88, 0.84], rimSpec: 1.1, sky: '#051e33', decor: 'orb', decorColor: '#f4efe6', ambient: 'bubbles' }),
  }),
  board({
    id: 'sunset_beach', name: 'Sunset Beach', price: 3500, rarity: 'rare', collection: 'nature',
    blurb: 'Golden sand, gentle waves and a sun going down.',
    look: look({ base: '#f2c48a', panel: '#fff3dc', panelEdge: '#f5c9a0', track: '#fffaf0', grid: '#40a0702a', safe: '#ff7a45', edge: '#7a4a2a', trim: '#ffcf7a',
      emblem: 'sun', pattern: 'sand', safeMark: 'pearl', wood: [0.6, 0.45, 0.26], rim: [0.78, 0.58, 0.34], rimSpec: 0.5, sky: '#5a2a3a', decor: 'tree', decorColor: '#3ab06a', ambient: 'none' }),
  }),
  board({
    id: 'sakura_garden', name: 'Sakura Garden', price: 4000, rarity: 'epic', collection: 'nature',
    blurb: 'A calm Japanese garden in full pink bloom.',
    look: look({ base: '#3a2a3a', panel: '#fff5f8', panelEdge: '#f2c4d4', track: '#ffffff', grid: '#40603a4a', safe: '#e04a7a', edge: '#241824', trim: '#c94a4a',
      emblem: 'blossom', pattern: 'petals', safeMark: 'flower', wood: [0.24, 0.12, 0.12], rim: [0.6, 0.12, 0.14], rimSpec: 0.6, sky: '#241824', decor: 'lantern', decorColor: '#ff6a5a', ambient: 'petals' }),
  }),
  board({
    id: 'mountain_paradise', name: 'Mountain Paradise', price: 4500, rarity: 'epic', collection: 'nature',
    blurb: 'Snowy peaks, pine trees and crisp mountain air.',
    look: look({ base: '#5a7a9a', panel: '#f0f6fb', panelEdge: '#b4cce0', track: '#ffffff', grid: '#40304a6a', safe: '#3a7a5a', edge: '#243444', trim: '#c4ccd6',
      emblem: 'mountain', pattern: 'snow', safeMark: 'snowflake', wood: [0.26, 0.2, 0.14], rim: [0.7, 0.74, 0.8], rimSpec: 0.8, sky: '#1a2a3a', decor: 'tree', decorColor: '#2a5a3a', ambient: 'snow' }),
  }),
  board({
    id: 'aurora_sky', name: 'Aurora Sky', price: 6000, rarity: 'legendary', collection: 'nature',
    blurb: 'Green and violet northern lights dancing over the board.',
    look: look({ base: '#0f1a3a', panel: '#1a2a5a', panelEdge: '#0a1230', track: '#f0f6ff', grid: '#55203a70', safe: '#5affc8', edge: '#050a1e', trim: '#9ab8e0',
      emblem: 'aurora', pattern: 'aurora', safeMark: 'star', wood: [0.05, 0.08, 0.18], rim: [0.6, 0.72, 0.9], rimSpec: 1, sky: '#050a1e', decor: 'orb', decorColor: '#5affc8', ambient: 'stars' }),
  }),

  // ------------------------------------------------------------------ Fantasy
  board({
    id: 'dragon_kingdom', name: 'Dragon Kingdom', price: 800, currency: 'gems', rarity: 'mythic', collection: 'fantasy',
    blurb: 'Dragon scales, dark stone and a golden dragon at the heart.',
    look: look({ base: '#2a1a14', panel: '#3a2a22', panelEdge: '#140c08', track: '#f6eee2', grid: '#66000000', safe: '#ff6a1e', edge: '#0a0604', trim: '#e0a020',
      emblem: 'dragon', pattern: 'scales', safeMark: 'flame', wood: [0.12, 0.07, 0.05], rim: [0.86, 0.6, 0.12], rimSpec: 1.1, sky: '#140604', decor: 'crystal', decorColor: '#ff6a1e', ambient: 'embers' }),
  }),
  board({
    id: 'ice_castle', name: 'Ice Castle', price: 6000, rarity: 'legendary', collection: 'fantasy',
    blurb: 'A frozen castle of glittering ice and snow.',
    look: look({ base: '#bfe6f5', panel: '#f4fcff', panelEdge: '#a8d8ee', track: '#ffffff', grid: '#40306a8a', safe: '#4ab0e0', edge: '#1a4a6a', trim: '#d6eeff',
      emblem: 'snowflake', pattern: 'snow', safeMark: 'snowflake', wood: [0.5, 0.66, 0.78], rim: [0.84, 0.93, 1], rimSpec: 1.2, sky: '#0f2a44', decor: 'crystal', decorColor: '#bff0ff', ambient: 'snow' }),
  }),
  board({
    id: 'fire_kingdom', name: 'Fire Kingdom', price: 6000, rarity: 'legendary', collection: 'fantasy',
    blurb: 'Volcanic rock, rivers of lava and flying embers.',
    look: look({ base: '#2a0f08', panel: '#4a1a0e', panelEdge: '#1a0604', track: '#fff1e2', grid: '#66000000', safe: '#ff8a1e', edge: '#0a0302', trim: '#ff8a1e',
      emblem: 'flame', pattern: 'flames', safeMark: 'flame', wood: [0.14, 0.05, 0.03], rim: [0.9, 0.36, 0.08], rimSpec: 0.8, sky: '#1a0402', decor: 'crystal', decorColor: '#ff5a1e', ambient: 'embers' }),
  }),
  board({
    id: 'galaxy_universe', name: 'Galaxy Universe', price: 700, currency: 'gems', rarity: 'mythic', collection: 'fantasy',
    blurb: 'Nebulae, stars and a ringed planet at the centre of it all.',
    look: look({ base: '#0a0620', panel: '#2a1a6e', panelEdge: '#07041a', track: '#f2f0ff', grid: '#55403a80', safe: '#9ef0ff', edge: '#030210', trim: '#b49aff',
      emblem: 'planet', pattern: 'nebula', safeMark: 'star', wood: [0.05, 0.03, 0.12], rim: [0.6, 0.5, 0.95], rimSpec: 1.1, sky: '#030210', decor: 'orb', decorColor: '#c77dff', ambient: 'stars' }),
  }),
  board({
    id: 'crystal_kingdom', name: 'Crystal Kingdom', price: 6500, rarity: 'legendary', collection: 'fantasy',
    blurb: 'Glowing violet crystals growing from every corner.',
    look: look({ base: '#3a1a6a', panel: '#f2eaff', panelEdge: '#c8a8f0', track: '#fbf8ff', grid: '#40503a8a', safe: '#9b4dff', edge: '#1a0a3a', trim: '#d6c4ff',
      emblem: 'crystal', pattern: 'crystals', safeMark: 'diamond', wood: [0.16, 0.08, 0.3], rim: [0.78, 0.68, 0.98], rimSpec: 1.2, sky: '#14062a', decor: 'crystal', decorColor: '#b07aff', ambient: 'sparkles' }),
  }),
  board({
    id: 'magic_forest', name: 'Magic Forest', price: 5000, rarity: 'epic', collection: 'fantasy',
    blurb: 'Glowing mushrooms, fireflies and an enchanted tree.',
    look: look({ base: '#1a3a2a', panel: '#2a5a3e', panelEdge: '#0f241a', track: '#f4fff6', grid: '#55002a10', safe: '#a8ff7a', edge: '#08140c', trim: '#a8e07a',
      emblem: 'tree', pattern: 'vines', safeMark: 'leaf', wood: [0.12, 0.2, 0.12], rim: [0.42, 0.62, 0.32], rimSpec: 0.6, sky: '#08140c', decor: 'lantern', decorColor: '#c8ff7a', ambient: 'fireflies' }),
  }),
  board({
    id: 'ancient_egypt', name: 'Ancient Egypt', price: 5500, rarity: 'legendary', collection: 'fantasy',
    blurb: 'Pharaoh gold, lapis blue, sand and hieroglyphs.',
    look: look({ base: '#d9b46a', panel: '#f6e6bc', panelEdge: '#d9b46a', track: '#fff8e6', grid: '#557a5a1a', safe: '#1a4fc4', edge: '#1a3a8a', trim: '#e0b23a',
      emblem: 'eye', pattern: 'hieroglyphs', safeMark: 'star', wood: [0.5, 0.36, 0.16], rim: [0.1, 0.24, 0.6], rimSpec: 0.8, sky: '#2a1a0a', decor: 'pillar', decorColor: '#e0b23a', ambient: 'sparkles' }),
  }),
  board({
    id: 'space_odyssey', name: 'Space Odyssey', price: 6500, rarity: 'legendary', collection: 'fantasy',
    blurb: 'A rocket launch among planets, steel and starlight.',
    look: look({ base: '#121826', panel: '#1e2a40', panelEdge: '#0a0f1a', track: '#eef3fa', grid: '#55203a5a', safe: '#ff5a3d', edge: '#05080e', trim: '#b4c4d6',
      emblem: 'rocket', pattern: 'stars', safeMark: 'star', wood: [0.08, 0.1, 0.14], rim: [0.7, 0.76, 0.84], rimSpec: 1.2, sky: '#05080e', decor: 'orb', decorColor: '#7fd8ff', ambient: 'stars' }),
  }),

  // ------------------------------------------------------------------ Neon
  board({
    id: 'neon_city', name: 'Neon City', price: 4500, rarity: 'epic', collection: 'neon',
    blurb: 'Glowing cyan and magenta lines over a midnight city.',
    look: look({ base: '#0a0a1e', panel: '#141438', panelEdge: '#070716', track: '#eaf6ff', grid: '#5539ffd6', safe: '#ff3df2', edge: '#000000', trim: '#39ffd6',
      emblem: 'lx', pattern: 'grid', safeMark: 'star', wood: [0.03, 0.03, 0.08], rim: [0.22, 1, 0.84], rimSpec: 0.8, sky: '#030308', decor: 'orb', decorColor: '#ff3df2', ambient: 'sparkles' }),
  }),
  board({
    id: 'cyber_grid', name: 'Cyber Grid', price: 4000, rarity: 'rare', collection: 'neon',
    blurb: 'An electric violet grid humming with light.',
    look: look({ base: '#120a2a', panel: '#22104a', panelEdge: '#0a0518', track: '#f2eeff', grid: '#55b05cff', safe: '#ffe600', edge: '#000000', trim: '#b05cff',
      emblem: 'lx', pattern: 'grid', safeMark: 'diamond', wood: [0.05, 0.03, 0.1], rim: [0.7, 0.36, 1], rimSpec: 0.8, sky: '#05020e', decor: 'crystal', decorColor: '#b05cff', ambient: 'sparkles' }),
  }),

  // ------------------------------------------------------------------ Seasonal
  board({
    id: 'winter_wonderland', name: 'Winter Wonderland', price: 3500, rarity: 'rare', collection: 'seasonal',
    blurb: 'Fresh snow, twinkling lights and cosy pines.',
    look: look({ base: '#dfeef8', panel: '#ffffff', panelEdge: '#cfe4f2', track: '#ffffff', grid: '#40406a8a', safe: '#d42a3a', edge: '#2a4a6a', trim: '#c8d8e8',
      emblem: 'snowflake', pattern: 'snow', safeMark: 'snowflake', wood: [0.5, 0.3, 0.18], rim: [0.86, 0.9, 0.96], sky: '#1a2a44', decor: 'tree', decorColor: '#2a6a3a', ambient: 'snow' }),
  }),
  board({
    id: 'spring_bloom', name: 'Spring Bloom', price: 3000, rarity: 'rare', collection: 'seasonal',
    blurb: 'Fresh flowers and soft green for a bright new season.',
    look: look({ base: '#c4e8a8', panel: '#fbfff4', panelEdge: '#d4f0b8', track: '#ffffff', grid: '#40406a2a', safe: '#ff7ab0', edge: '#2a5a1a', trim: '#f2d27a',
      emblem: 'flower', pattern: 'petals', safeMark: 'flower', wood: [0.36, 0.5, 0.22], rim: [0.95, 0.82, 0.48], sky: '#1f3a12', decor: 'tree', decorColor: '#ff9ccf', ambient: 'petals' }),
  }),
  board({
    id: 'festival_lights', name: 'Festival Lights', price: 3500, rarity: 'rare', collection: 'seasonal',
    blurb: 'Lanterns, warm lights and a festive night sky.',
    look: look({ base: '#2a1446', panel: '#3a1e5e', panelEdge: '#160a2a', track: '#fff6e6', grid: '#55200a3a', safe: '#ffcf40', edge: '#0a0418', trim: '#ffb347',
      emblem: 'lantern', pattern: 'lights', safeMark: 'star', wood: [0.16, 0.08, 0.24], rim: [1, 0.7, 0.28], sky: '#0a0418', decor: 'lantern', decorColor: '#ffb347', ambient: 'fireflies' }),
  }),

  // ------------------------------------------------------------------ Exclusive Legendary
  board({
    id: 'bangladesh', name: 'Bangladesh', price: 2500, rarity: 'epic', collection: 'exclusive', added: OLD, badge: '🇧🇩 DESH',
    blurb: 'Green and the red sun — play for your country.',
    look: look({ base: '#006a4e', panel: '#0a7a5a', panelEdge: '#005038', track: '#ffffff', grid: '#40004d38', safe: '#f42a41', edge: '#003a29', trim: '#e9c46a',
      emblem: 'flag', pattern: 'flag', wood: [0, 0.24, 0.17], rim: [0, 0.45, 0.32], rimSpec: 0.6, sky: '#00261b', decor: 'orb', decorColor: '#f42a41' }),
  }),
  board({
    id: 'ludox_legend', name: 'LudoX Legend', price: 1500, currency: 'gems', rarity: 'ultimate', collection: 'exclusive', badge: '👑 ULTIMATE',
    blurb: 'The LudoX signature board: royal purple, gold and gems.',
    look: look({ base: '#2a0a5e', panel: '#5a2ab0', panelEdge: '#1e0548', track: '#fbf6ff', grid: '#556a1fd0', safe: '#ffcf40', edge: '#0f0228', trim: '#ffcf40',
      emblem: 'lx', pattern: 'damask', wood: [0.12, 0.04, 0.3], rim: [1, 0.8, 0.26], rimSpec: 1.3, sky: '#0f0228', decor: 'crystal', decorColor: '#c77dff', ambient: 'sparkles' }),
  }),
];

/** Boards an administrator created (kept in game settings; the server fills this in). */
export const CUSTOM_BOARDS: BoardTheme[] = [];

export function boardTheme(id: string | null | undefined): BoardTheme | undefined {
  return BOARDS.find((b) => b.id === id) ?? CUSTOM_BOARDS.find((b) => b.id === id);
}
