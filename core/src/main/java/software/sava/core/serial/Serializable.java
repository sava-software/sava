package software.sava.core.serial;

public interface Serializable {

  int l();

  int write(byte[] data, int offset);

  default int write(final byte[] data) {
    return write(data, 0);
  }

  default byte[] write() {
    final byte[] data = new byte[l()];
    write(data);
    return data;
  }

  default Serializable reusable() {
    return new Serialized(write());
  }
}
