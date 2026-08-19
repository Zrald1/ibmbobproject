# Contributing to Argos

Thank you for your interest in contributing to Argos! This document outlines the guidelines for contributing.

## Getting Started

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature-name`
3. Make your changes
4. Test thoroughly on a real device (emulators are blocked by the security layer)
5. Commit with a clear message: `git commit -m "feat: add your feature"`
6. Push: `git push origin feature/your-feature-name`
7. Open a Pull Request

## Code Style

- Follow existing Java naming conventions (camelCase for methods/variables, PascalCase for classes)
- Keep methods focused and readable
- Add comments for complex logic only — avoid stating the obvious
- Match the existing indentation (4 spaces)

## Commit Messages

Use conventional commits:

- `feat:` new feature
- `fix:` bug fix
- `security:` security improvement
- `refactor:` code restructuring
- `docs:` documentation only
- `test:` test additions

## Security Considerations

- **Never** commit API keys, secrets, or credentials
- **Never** weaken security checks (root detection, pirate app detection, etc.)
- Report security vulnerabilities privately — see [SECURITY.md](SECURITY.md)
- Test changes against rooted devices, emulators, and pirate apps

## Pull Request Checklist

- [ ] Code compiles without errors
- [ ] Tested on a physical Android device
- [ ] No secrets or credentials in the diff
- [ ] Commit messages follow conventional commits
- [ ] README updated if needed

## Areas for Contribution

- Additional language support for UI strings
- New tool implementations for the AI agent
- Security hardening improvements
- UI/UX enhancements
- Performance optimizations
- Bug fixes

## Questions?

Open an issue with the `question` label.
