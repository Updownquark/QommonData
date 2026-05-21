package org.qommons.data.types;

import static org.qommons.data.types.Blob.printHex;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import org.qommons.StringUtils;
import org.qommons.StringUtils.BinaryDataEncoder;
import org.qommons.Subscription;
import org.qommons.collect.ListenerList;
import org.qommons.ex.ExConsumer;
import org.qommons.ex.ExRunnable;
import org.qommons.io.CircularByteBuffer;
import org.qommons.io.UnfailingInputStream;
import org.qommons.io.UnfailingOutputStream;

public interface Blob {
	long length();

	long getLastModified();

	InputStream read() throws IOException;

	InputStream read(int offset) throws IOException;

	OutputStream write() throws IOException;

	default Blob write(ExConsumer<OutputStream, IOException> write) throws IOException {
		try (OutputStream out = write()) {
			write.accept(out);
		}
		return this;
	}

	void clear() throws IOException;

	default Reader readChars() throws IOException {
		return new InputStreamReader(read(), StandardCharsets.UTF_8);
	}

	default Writer writeChars() throws IOException {
		return new OutputStreamWriter(write(), StandardCharsets.UTF_8);
	}

	default Blob writeChars(ExConsumer<Writer, IOException> write) throws IOException {
		try (Writer out = writeChars()) {
			write.accept(out);
		}
		return this;
	}

	Subscription onChange(ExRunnable<IOException> listener);

	static String printHex(Blob blob, int maxBytes) {
		BinaryDataEncoder encoder = StringUtils.encodeHex();
		StringBuilder text = new StringBuilder();
		try (InputStream in = blob.read()) {
			int byteCount = 0;
			int b = in.read();
			while (b >= 0) {
				if (byteCount >= maxBytes)
					return text.append("+").append(blob.length() - byteCount).append("...").toString();
				encoder.format(text, (byte) b);
				b = in.read();
			}
			return text.toString();
		} catch (IOException e) {
			return text.append("!").append(e.getMessage()).toString();
		}
	}

	public static class InMemoryBlob implements Blob {
		private final CircularByteBuffer theBuffer;
		private final ListenerList<ExRunnable<IOException>> theListeners;
		private long theLastModified;

		public InMemoryBlob() {
			theBuffer = new CircularByteBuffer(-1);
			theListeners = ListenerList.build().build();
		}

		@Override
		public long length() {
			return theBuffer.length();
		}

		@Override
		public long getLastModified() {
			return theLastModified;
		}

		@Override
		public UnfailingInputStream read() {
			return theBuffer.asInputStream();
		}

		@Override
		public UnfailingInputStream read(int offset) {
			return theBuffer.asInputStream(offset);
		}

		@Override
		public UnfailingOutputStream write() {
			theLastModified = System.currentTimeMillis();
			theBuffer.clear(false);
			return new UnfailingListenableOutputStream(theBuffer.asOutputStream(), this::fireChanged);
		}

		private void fireChanged() {
			theListeners.forEach(l -> {
				try {
					l.run();
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
		}

		@Override
		public Reader readChars() {
			try {
				return Blob.super.readChars();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public Writer writeChars() {
			try {
				return Blob.super.writeChars();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public void clear() throws IOException {
			if (theBuffer.length() > 0) {
				theBuffer.clear(false);
				fireChanged();
			}
		}

		@Override
		public Subscription onChange(ExRunnable<IOException> listener) {
			return theListeners.add(listener, false);
		}

		@Override
		public String toString() {
			return printHex(this, 100);
		}
	}

	public static class ListenableOutputStream extends OutputStream {
		private final OutputStream theWrapped;
		private final ExRunnable<IOException> onClose;

		public ListenableOutputStream(OutputStream wrapped, ExRunnable<IOException> onClose) {
			theWrapped = wrapped;
			this.onClose = onClose;
		}

		@Override
		public void write(int b) throws IOException {
			theWrapped.write(b);
		}

		@Override
		public void write(byte[] b) throws IOException {
			theWrapped.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			theWrapped.write(b, off, len);
		}

		@Override
		public void flush() throws IOException {
			theWrapped.flush();
		}

		@Override
		public void close() throws IOException {
			theWrapped.close();
			onClose.run();
		}
	}

	public static class UnfailingListenableOutputStream extends UnfailingOutputStream {
		private final UnfailingOutputStream theWrapped;
		private Runnable onClose;

		public UnfailingListenableOutputStream(UnfailingOutputStream wrapped, Runnable onClose) {
			theWrapped = wrapped;
			this.onClose = onClose;
		}

		@Override
		public void write(int b) {
			theWrapped.write(b);
		}

		@Override
		public void write(byte[] b) {
			theWrapped.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) {
			theWrapped.write(b, off, len);
		}

		@Override
		public void flush() {
			theWrapped.flush();
		}

		@Override
		public void close() {
			theWrapped.close();
			onClose.run();
		}
	}
}
