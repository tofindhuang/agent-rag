import { AuthGate } from "@/components/auth/AuthGate";
import { AdminPage } from "@/components/admin/AdminPage";

export default function Page() {
  return (
    <AuthGate>
      <AdminPage />
    </AuthGate>
  );
}
