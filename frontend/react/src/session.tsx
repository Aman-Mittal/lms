/*
 * Copyright 2026 Aman Mittal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { createContext, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { createGeneratedGateway } from './gateway/generatedGateway';
import type { LmsGateway, SignedIn } from './gateway/lmsGateway';

/*
 * REACT CONCEPT: context.
 * State lives in the component that owns it, and is passed down as props.
 * When many distant components need the same thing -- here, "who is signed in"
 * -- passing props through every layer is tedious. Context lets a provider
 * near the top expose a value that any component below can read with a hook.
 */
interface SessionValue {
  auth: SignedIn | null;
  gateway: LmsGateway;
  start: (result: SignedIn) => void;
  end: () => void;
}

const SessionContext = createContext<SessionValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  // useState: a value React remembers between renders. Calling the setter
  // (setAuth) asks React to render again with the new value.
  // Held in memory only: a token in localStorage is readable by any script.
  const [auth, setAuth] = useState<SignedIn | null>(null);

  // useRef: a box whose contents can change without causing a re-render. The
  // gateway reads the token through it, so one gateway serves every sign-in.
  const tokenRef = useRef<string | null>(null);
  tokenRef.current = auth?.token ?? null;

  // useMemo: compute once, reuse until the dependencies change (never, here).
  const gateway = useMemo(() => createGeneratedGateway(() => tokenRef.current), []);

  const value: SessionValue = { auth, gateway, start: setAuth, end: () => setAuth(null) };
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

/** A custom hook: an ordinary function that uses other hooks. */
export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) {
    throw new Error('useSession must be used inside <SessionProvider>');
  }
  return value;
}
