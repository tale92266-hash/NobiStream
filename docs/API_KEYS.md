# Getting your Telegram API keys (api_id and api_hash)

NobiStream signs in with your own Telegram account. To do that, Telegram needs you to create a free API app first. This takes about 2 minutes. You only do it once.

## Steps

1. Open **https://my.telegram.org** in a browser on your phone or computer.
2. Log in with your Telegram phone number. Telegram will send you a code — enter it on the website.
3. Click **"API development tools"**.
4. Fill in the form:
   - **App title:** `Nobi Stream`
   - **Short name:** `NobiStream`
   - **Platform:** `Android`
   - Leave the other fields as they are (you can leave the URL field blank).
   - Tap **Create application**.
5. You will see two values on the next page. Copy them:
   - **api_id** — a number (for example `12345678`).
   - **api_hash** — a long code made of letters and numbers, 32 characters long.
6. Open **NobiStream** on your phone, go to **Settings**, paste the api_id and api_hash, and tap **Save**.
7. Go back and sign in with your phone number. Telegram will send you a code — enter it in the app. Done.

## Warnings — please read

- **Never share your api_id and api_hash with anyone.** They belong to your Telegram account.
- **Never send them in a chat** — not to me, not to anyone. They stay only on your phone, inside the app's Settings.
- If someone else gets them, they can pretend to be your app. Keep them private, like a password.
