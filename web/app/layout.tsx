import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "agent-core",
  description: "Conversational knowledge base powered by pi",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh">
      <body>{children}</body>
    </html>
  );
}
