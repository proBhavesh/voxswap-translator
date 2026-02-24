/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: {
        brand: {
          primary: '#4F46E5',
          'primary-light': '#6366F1',
          'primary-dark': '#4338CA',
          accent: '#0EA5E9',
          'accent-dark': '#0284C7',
        },
        text: {
          primary: '#111827',
          secondary: '#6B7280',
          muted: '#9CA3AF',
          inverse: '#FFFFFF',
          accent: '#4F46E5',
        },
        bg: {
          primary: '#FFFFFF',
          secondary: '#F9FAFB',
          card: '#F3F4F6',
          elevated: '#FFFFFF',
        },
        status: {
          success: '#16A34A',
          'success-muted': '#DCFCE7',
          error: '#DC2626',
          'error-muted': '#FEE2E2',
          warning: '#D97706',
          'warning-muted': '#FEF3C7',
          info: '#2563EB',
          'info-muted': '#DBEAFE',
        },
        border: {
          light: '#F3F4F6',
          DEFAULT: '#E5E7EB',
          dark: '#D1D5DB',
        },
      },
      borderRadius: {
        sm: '4px',
        md: '8px',
        lg: '12px',
        xl: '16px',
        '2xl': '24px',
      },
    },
  },
  plugins: [],
};
