/**
 * Profile frames: animated rings around a player's picture, bought with diamonds in the
 * shop and shown wherever their avatar is (profile, friends, rooms, the game table).
 * The server owns the catalogue; clients draw each frame by its id. `price` is the default
 * an administrator can change (settings → shop prices).
 */
export interface ProfileFrame {
  id: string;
  name: string;
  /** Default price in diamonds. */
  price: number;
}

export const PROFILE_FRAMES: ProfileFrame[] = [
  { id: 'fire', name: 'Fire Ring', price: 120 },
  { id: 'hearts', name: 'Love Hearts', price: 80 },
  { id: 'neon', name: 'Neon Pulse', price: 60 },
  { id: 'gold', name: 'Royal Gold', price: 150 },
  { id: 'frost', name: 'Frost', price: 80 },
  { id: 'rainbow', name: 'Rainbow', price: 100 },
];

/** Equipping this id takes the frame off. */
export const NO_FRAME = 'none';

export function profileFrame(id: string | null | undefined): ProfileFrame | undefined {
  return PROFILE_FRAMES.find((f) => f.id === id);
}
