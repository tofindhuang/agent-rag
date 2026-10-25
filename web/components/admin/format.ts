export function formatNumber(value: number): string {
  return new Intl.NumberFormat("zh-CN").format(value);
}

export function formatCost(value: number, currency: string): string {
  const symbol = currency === "CNY" ? "¥" : currency === "USD" ? "$" : `${currency} `;
  return `${symbol}${value.toFixed(4)}`;
}

export function formatTime(value: string | null): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("zh-CN");
}
