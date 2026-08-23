# Required protection for `main`

GitHub branch protection is a repository setting and cannot be applied from this
repository without an administrator token. Configure the `main` branch rule as
follows after the `Verify` workflow has run once:

- Require a pull request before merging.
- Require status checks to pass before merging.
- Select the `verify` status check from the **Verify** workflow.
- Require branches to be up to date before merging.
- Do not allow force pushes or deletions.

The workflow runs `mvn -B verify` on every push and on pull requests targeting
`main`. Tests requiring the private workbook self-skip when it is absent, so
public CI verifies the unit and synthetic-fixture suite.
