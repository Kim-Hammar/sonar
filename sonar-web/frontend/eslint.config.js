import js from '@eslint/js'
import prettier from 'eslint-config-prettier'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'

const noComments = {
  meta: {
    type: 'suggestion',
    schema: [],
    messages: {
      comment: 'Only JSDoc comments are allowed; document components and functions with /** */.'
    }
  },
  create(context) {
    return {
      Program() {
        for (const comment of context.sourceCode.getAllComments()) {
          if (comment.type !== 'Block' || !comment.value.startsWith('*')) {
            context.report({ loc: comment.loc, messageId: 'comment' })
          }
        }
      }
    }
  }
}

export default [
  { ignores: ['dist', 'node_modules', 'coverage'] },
  js.configs.recommended,
  reactHooks.configs.flat['recommended-latest'],
  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser },
      parserOptions: { ecmaFeatures: { jsx: true } }
    },
    plugins: { sonar: { rules: { 'no-comments': noComments } } },
    rules: {
      'sonar/no-comments': 'error',
      'no-unused-vars': ['error', { varsIgnorePattern: '^_', argsIgnorePattern: '^_' }]
    }
  },
  {
    files: ['*.config.js', 'src/test/**', '**/*.test.{js,jsx}'],
    languageOptions: { globals: { ...globals.node } }
  },
  prettier
]
