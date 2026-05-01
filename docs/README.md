# EXIFiler Website

This directory contains the GitHub Pages website for EXIFiler.com.

## Files

- `index.html` - Main website page
- `styles.css` - Website styling
- `CNAME` - Custom domain configuration (EXIFiler.com)

## Setup

To enable GitHub Pages for the custom domain:

1. Go to repository Settings → Pages
2. Under "Source", select "GitHub Actions"
3. Save the settings

The GitHub Actions workflow (`.github/workflows/pages.yml`) will automatically deploy changes to the website when you push updates to the `docs/` directory.

## Custom Domain Setup

To configure EXIFiler.com to point to this GitHub Pages site:

1. In your domain registrar (where you bought EXIFiler.com), add the following DNS records:

   **For apex domain (EXIFiler.com):**
   ```
   Type: A
   Name: @
   Value: 185.199.108.153

   Type: A
   Name: @
   Value: 185.199.109.153

   Type: A
   Name: @
   Value: 185.199.110.153

   Type: A
   Name: @
   Value: 185.199.111.153
   ```

   **For www subdomain (optional):**
   ```
   Type: CNAME
   Name: www
   Value: lookatwhatAiCando.github.io
   ```

2. Wait for DNS propagation (can take up to 48 hours, usually much faster)

3. In GitHub repository Settings → Pages → Custom domain, enter `EXIFiler.com` and save

4. Enable "Enforce HTTPS" once the certificate is provisioned

## Local Development

To preview the website locally, simply open `index.html` in a web browser, or use a local server:

```bash
# Using Python
cd docs
python3 -m http.server 8000

# Using Node.js
npx serve docs

# Using PHP
php -S localhost:8000 -t docs
```

Then visit http://localhost:8000 in your browser.

## Previewing a Pull Request Branch

The GitHub Pages site is only deployed when changes are merged to `main`. To review
website changes in a PR before merging, use one of the following approaches:

### Option 1 — Quick in-browser preview (no cloning needed)

Paste the URL below into your browser, replacing `BRANCH_NAME` with the PR branch name
(e.g. `copilot/de-emphasize-meta-ai-glasses`):

```
https://htmlpreview.github.io/?https://github.com/LookAtWhatAiCanDo/EXIFiler/blob/BRANCH_NAME/docs/index.html
```

> **Note:** Use the GitHub `blob/` URL format shown above (not `raw.githubusercontent.com`).
> Branch names containing `/` (e.g. `copilot/de-emphasize-meta-ai-glasses`) work as-is.

### Option 2 — Check out the branch with the `gh` CLI

```bash
# Check out the PR branch (replace <PR_NUMBER> with the actual number)
gh pr checkout <PR_NUMBER>

# Serve the docs locally
cd docs
python3 -m http.server 8000
```

Then visit http://localhost:8000 in your browser.

### Option 3 — Check out the branch with plain Git

```bash
git fetch origin
git checkout origin/BRANCH_NAME -- docs/

# Serve the docs locally
cd docs
python3 -m http.server 8000
```

Then visit http://localhost:8000 in your browser.
When you are done, restore the previous state with `git checkout HEAD -- docs/`.
