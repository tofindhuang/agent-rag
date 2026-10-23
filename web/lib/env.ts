/**
 * Public configuration only. NEXT_PUBLIC_* values are inlined at build time;
 * never put secrets here.
 */
export const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";
