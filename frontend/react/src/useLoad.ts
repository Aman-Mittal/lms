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

import { useEffect, useState } from 'react';

/*
 * REACT CONCEPT: useEffect.
 * Rendering must be a pure calculation from props and state. Anything that
 * reaches outside -- a network call -- belongs in an effect, which React runs
 * after rendering. The dependency array says when to run it again: here,
 * whenever `reloadKey` changes.
 */
export function useLoad<T>(load: () => Promise<T>, reloadKey: unknown) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // The cleanup function (returned below) runs before the next effect and on
    // unmount. `ignore` stops an old, slow response overwriting a newer one.
    let ignore = false;
    setLoading(true);
    setError('');
    load()
      .then((result) => { if (!ignore) { setData(result); } })
      .catch((e: Error) => { if (!ignore) { setError(e.message); } })
      .finally(() => { if (!ignore) { setLoading(false); } });
    return () => { ignore = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reloadKey]);

  return { data, error, loading };
}
