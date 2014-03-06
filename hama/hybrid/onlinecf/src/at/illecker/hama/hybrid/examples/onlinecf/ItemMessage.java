/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package at.illecker.hama.hybrid.examples.onlinecf;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.Text;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DenseDoubleVector;
import org.apache.hama.commons.math.DoubleVector;

public final class ItemMessage extends VectorWritable {
  public final static String VALUE_DELIMITER = ",";

  private long senderId;
  private long itemId;

  public ItemMessage() {
    super();
  }

  public ItemMessage(long senderId, long itemId, VectorWritable vector) {
    super(vector);
    this.senderId = senderId;
    this.itemId = itemId;
  }

  public ItemMessage(long senderId, long itemId, DoubleVector vector) {
    super(vector);
    this.senderId = senderId;
    this.itemId = itemId;
  }

  public long getSenderId() {
    return this.senderId;
  }

  public void setSenderId(long senderId) {
    this.senderId = senderId;
  }

  public long getItemId() {
    return this.itemId;
  }

  public void setItemId(long itemId) {
    this.itemId = itemId;
  }

  @Override
  public final void write(DataOutput out) throws IOException {
    writeItemMessage(this, out);
  }

  @Override
  public final void readFields(DataInput in) throws IOException {
    ItemMessage itemMessage = readItemMessage(in);
    super.set(itemMessage.getVector());
    this.senderId = itemMessage.getSenderId();
    this.itemId = itemMessage.getItemId();
  }

  public static void writeItemMessage(ItemMessage itemMessage, DataOutput out)
      throws IOException {
    String str = itemMessage.senderId + VALUE_DELIMITER + itemMessage.itemId
        + VALUE_DELIMITER;
    DoubleVector vector = itemMessage.getVector();
    for (int i = 0; i < vector.getLength(); i++) {
      str += (i < vector.getLength() - 1) ? vector.get(i) + VALUE_DELIMITER
          : vector.get(i);
    }
    Text.writeString(out, str);
  }

  public static ItemMessage readItemMessage(DataInput in) throws IOException {
    String str = Text.readString(in);
    String[] values = str.split(VALUE_DELIMITER);
    int senderId = Integer.parseInt(values[0]);
    long itemId = Long.parseLong(values[1]);

    DoubleVector vector = new DenseDoubleVector(values.length - 2);
    for (int i = 0; i < values.length - 2; i++) {
      vector.set(i, Double.parseDouble(values[i + 2]));
    }
    return new ItemMessage(senderId, itemId, vector);
  }
}
