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
