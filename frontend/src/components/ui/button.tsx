import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const buttonVariants = cva('', {
  variants: {
    variant: {
      unstyled: '',
      default: 'button button-default',
      primary: 'button button-primary',
    },
  },
  defaultVariants: {
    variant: 'unstyled',
  },
})

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonVariants> {}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, type = 'button', ...props }, ref) => (
    <button type={type} className={cn(buttonVariants({ variant, className }))} ref={ref} {...props} />
  ),
)
Button.displayName = 'Button'

export { Button }
