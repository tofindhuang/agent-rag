import { AuthGate } from "@/components/auth/AuthGate";
import { KnowledgePage } from "@/components/knowledge/KnowledgePage";

export default function Page() {
  return (
    <AuthGate>
      <KnowledgePage />
    </AuthGate>
  );
}
