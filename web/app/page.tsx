import { AuthGate } from "@/components/auth/AuthGate";
import { ChatPage } from "@/components/chat/ChatPage";

export default function Page() {
  return (
    <AuthGate>
      <ChatPage />
    </AuthGate>
  );
}
