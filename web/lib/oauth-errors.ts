/** Stable OAuth callback codes mapped to account/login copy. */
const OAUTH_ERROR_MESSAGES: Record<string, string> = {
  OAUTH_DENIED: "已取消登录。",
  OAUTH_UNAVAILABLE: "OAuth 提供方暂不可用，请稍后重试或使用密码登录。",
  OAUTH_STATE_INVALID: "登录状态已失效，请重新登录。",
  OAUTH_EMAIL_UNVERIFIED: "OAuth 返回的邮箱未验证，无法登录。",
  OAUTH_EMAIL_MISMATCH: "OAuth 邮箱与当前账号不一致。",
  OAUTH_ACCOUNT_EXISTS: "该邮箱已注册，请先用邮箱密码登录后在设置页绑定 OAuth。",
  OAUTH_IDENTITY_IN_USE: "该 OAuth 身份已绑定其他账号。",
  OAUTH_ALREADY_LINKED: "当前账号已绑定该提供方的另一个身份，请先解绑。",
  OAUTH_CLAIM_MISMATCH: "OAuth 返回的身份信息不一致，登录已取消。",
  OAUTH_DISABLED: "OAuth 未启用。",
  LAST_LOGIN_METHOD: "请先设置密码或绑定其他登录方式，再解绑。",
};

export function oauthErrorMessage(code: string | null | undefined): string | null {
  if (!code) return null;
  return OAUTH_ERROR_MESSAGES[code] ?? "OAuth 操作失败，请重试。";
}
