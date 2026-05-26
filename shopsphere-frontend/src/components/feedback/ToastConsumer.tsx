import { useEffect } from 'react';
import { message } from 'antd';
import { useUiStore } from '@/store/ui';

export function ToastConsumer() {
  const [api, contextHolder] = message.useMessage();
  const toasts = useUiStore((s) => s.toasts);
  const consume = useUiStore((s) => s.consumeToast);

  useEffect(() => {
    if (toasts.length === 0) return;
    const t = toasts[0]!;
    api.open({
      type: t.type,
      content: t.description || t.message,
      duration: t.duration ?? 3,
    });
    consume(t.id);
  }, [toasts, api, consume]);

  return contextHolder;
}
