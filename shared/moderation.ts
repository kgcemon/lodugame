/** Reporting players: what a player can report, and how much they can say. */

export const REPORT_REASONS = ['harassment', 'hate', 'sexual', 'spam', 'cheating', 'other'] as const;
export type ReportReason = (typeof REPORT_REASONS)[number];

export const REPORT_STATUSES = ['open', 'resolved', 'dismissed'] as const;
export type ReportStatus = (typeof REPORT_STATUSES)[number];

export const MAX_REPORT_NOTE = 500;
/** How many of the reported player's latest room messages are kept with a report. */
export const REPORT_EVIDENCE_MESSAGES = 20;

/** One chat line kept with a report: the server's copy, never what the reporter claims was said. */
export interface ReportedMessage {
  text: string;
  at: number;
  quick: boolean;
}
