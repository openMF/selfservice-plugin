/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.contentstore.processor;

import java.io.FilterInputStream;
import java.io.IOException;

/**
 * Content processor that counts the bytes read through the stream and exposes the total under
 * {@link #SIZE_RESULT_VALUE} in the processing context once the stream is closed.
 * <p>
 * This is a minimal compatible implementation provided by the self-service plugin so that the
 * application context can start when the bundled Fineract core no longer ships
 * {@code SizeContentProcessor} on its classpath. It mirrors the behavior of the Fineract core
 * class: a {@link ByteCountingInputStream} counts bytes as they are read and stores the result as
 * a {@code Long} in the context when the stream is closed.
 */
public class SizeContentProcessor implements ContentProcessor {

    public static final String SIZE_RESULT_VALUE = "size.result.value";

    public SizeContentProcessor() {
    }

    @Override
    public ContentProcessorContext process(ContentProcessorContext context) {
        return context.clone(new ByteCountingInputStream(context));
    }

    final class ByteCountingInputStream extends FilterInputStream {

        private final ContentProcessorContext ctx;
        private long byteCount;

        ByteCountingInputStream(ContentProcessorContext ctx) {
            super(ctx.getInputStream());
            this.ctx = ctx;
            this.byteCount = 0L;
        }

        @Override
        public int read() throws IOException {
            int read = super.read();
            if (read != -1) {
                byteCount++;
            }
            return read;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read != -1) {
                byteCount += read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            try {
                ctx.setResult(SIZE_RESULT_VALUE, Long.valueOf(byteCount));
            } finally {
                super.close();
            }
        }
    }
}
