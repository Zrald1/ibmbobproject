# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Older versions | No |

## Reporting a Vulnerability

If you discover a security vulnerability in Argos, please report it responsibly:

1. **Do NOT** open a public GitHub issue
2. Email: `security@your-domain.example.com`
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

You will receive a response within 48 hours. If the vulnerability is confirmed, a fix will be prioritized and a security advisory will be published.

## Security Measures

Argos implements multiple layers of security:

### Root Detection
- Checks for `su` binary in standard locations
- Detects Magisk, SuperSU, and root management apps
- Checks for unlocked bootloader via `ro.boot.verifiedbootstate`
- Scans `/proc/self/mountinfo` for Magisk overlays
- Detects Magisk Hide / Zygisk via `/proc/self/maps`

### Pirate App Detection
- Scans for Lucky Patcher and variants (by package name and substring matching)
- Detects billing emulation services
- Catches randomized package names via substring heuristics

### APK Tampering
- Verifies APK signature hash
- Detects multiple signatures (repackaging indicator)
- Checks installer source

### Emulator Detection
- Checks Build.FINGERPRINT, MODEL, HARDWARE, PRODUCT
- Detects QEMU-specific files

### Hook Framework Detection
- Detects Xposed, LSPosed, EdXposed via package and class loading
- Detects Frida server via process scanning

## Disclosure Timeline

- **Day 0**: Vulnerability reported
- **Day 1**: Acknowledgment sent
- **Day 7**: Initial assessment and triage
- **Day 30**: Fix released (or ETA communicated)
- **Day 90**: Public disclosure (if not already patched)
