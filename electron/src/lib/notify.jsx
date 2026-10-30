import { useCallback } from 'react'
import { Toast, ToastBody, ToastTitle, useToastController } from '@fluentui/react-components'

export const TOASTER_ID = 'ytdd-toaster'

/** notify({ intent, title, body }) - a Fluent toast, from anywhere under <Toaster>. */
export function useNotify() {
  const { dispatchToast } = useToastController(TOASTER_ID)
  return useCallback(
    ({ intent = 'info', title, body, timeout = 4000 }) => {
      dispatchToast(
        <Toast>
          <ToastTitle>{title}</ToastTitle>
          {body && <ToastBody>{body}</ToastBody>}
        </Toast>,
        { intent, timeout }
      )
    },
    [dispatchToast]
  )
}
