import { keycloak, refreshToken } from "@/app/api/auth/[...nextauth]/keycloak";

import { logger } from "@/utils/logger";
import { AuthOptions } from "next-auth";

const log = logger.child({ module: "auth" });
export const authOptions: AuthOptions = {
  providers: [keycloak],
  callbacks: {
    async jwt({ token, account }) {
      // Persist the OAuth access_token and or the user id to the token right after signin
      if (account) {
        log.trace("account present, saving new token");
        // Save the access token and refresh token in the JWT on the initial login
        return {
          access_token: account.access_token,
          expires_at: account.expires_at,
          refresh_token: account.refresh_token,
          email: token.email,
          name: token.name,
          picture: token.picture,
          sub: token.sub,
        };
      }

      return refreshToken(token);
    },
    async session({ session, token }) {
      // Send properties to the client, like an access_token from a provider.
      log.trace(token, "Creating session from token");
      return {
        ...session,
        error: token.error,
        accessToken: token.access_token,
      };
    },
  },
};