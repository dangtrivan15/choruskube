import { useState, useEffect, useCallback } from 'react';
import { useLocation } from 'react-router';

export function useSidebarDrawer() {
  const [isOpen, setIsOpen] = useState(false);
  const location = useLocation();

  // Auto-close on route change
  useEffect(() => {
    setIsOpen(false);
  }, [location.pathname]);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);
  const toggle = useCallback(() => setIsOpen(prev => !prev), []);

  return { isOpen, open, close, toggle };
}
