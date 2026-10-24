import type { Metadata } from "next";
import { AuthProvider } from "@/components/auth/AuthProvider";
import "./globals.css";

export const metadata: Metadata = {
  title: "agent-core",
  description: "Conversational knowledge base powered by pi",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh" suppressHydrationWarning>
      <head>
        {/*
          First-frame theme (ADR-0012): the only inline side effect allowed here.
          It must run before CSS paints so an explicit light choice does not flash
          dark. "system" writes nothing and lets prefers-color-scheme decide.
        */}
        <script
          dangerouslySetInnerHTML={{
            __html:
              '(function(){try{var p=localStorage.getItem("agentcore.theme");if(p==="light"||p==="dark"){document.documentElement.dataset.theme=p}}catch(e){}})()',
          }}
        />
      </head>
      <body>
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
