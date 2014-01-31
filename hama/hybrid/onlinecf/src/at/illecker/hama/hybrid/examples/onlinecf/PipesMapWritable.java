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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.AbstractMapWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.ReflectionUtils;

public final class PipesMapWritable extends AbstractMapWritable implements
    Map<Writable, Writable> {

  private static final Log LOG = LogFactory.getLog(PipesMapWritable.class);
  public final static String PAIR_DELIMITER = ":";
  public final static String VALUE_DELIMITER = ",";

  private Map<Writable, Writable> instance;

  /** Default constructor. */
  public PipesMapWritable() {
    super();
    this.instance = new HashMap<Writable, Writable>();
  }

  /**
   * Copy constructor.
   * 
   * @param other the map to copy from
   */
  public PipesMapWritable(MapWritable other) {
    this();
    copy(other);
  }

  /** {@inheritDoc} */
  public void clear() {
    instance.clear();
  }

  /** {@inheritDoc} */
  public boolean containsKey(Object key) {
    return instance.containsKey(key);
  }

  /** {@inheritDoc} */
  public boolean containsValue(Object value) {
    return instance.containsValue(value);
  }

  /** {@inheritDoc} */
  public Set<Map.Entry<Writable, Writable>> entrySet() {
    return instance.entrySet();
  }

  /** {@inheritDoc} */
  public Writable get(Object key) {
    return instance.get(key);
  }

  /** {@inheritDoc} */
  public boolean isEmpty() {
    return instance.isEmpty();
  }

  /** {@inheritDoc} */
  public Set<Writable> keySet() {
    return instance.keySet();
  }

  /** {@inheritDoc} */
  @SuppressWarnings("unchecked")
  public Writable put(Writable key, Writable value) {
    addToMap(key.getClass());
    addToMap(value.getClass());
    return instance.put(key, value);
  }

  /** {@inheritDoc} */
  public void putAll(Map<? extends Writable, ? extends Writable> t) {
    for (Map.Entry<? extends Writable, ? extends Writable> e : t.entrySet()) {
      put(e.getKey(), e.getValue());
    }
  }

  /** {@inheritDoc} */
  public Writable remove(Object key) {
    return instance.remove(key);
  }

  /** {@inheritDoc} */
  public int size() {
    return instance.size();
  }

  /** {@inheritDoc} */
  public Collection<Writable> values() {
    return instance.values();
  }

  public static String writableToString(Writable w) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ObjectOutputStream oos = new ObjectOutputStream(bos);
    w.write(oos);
    oos.close();
    return new String(bos.toByteArray());
  }

  public static Writable stringToWritable(Writable w, String str)
      throws IOException {
    ByteArrayInputStream bis = new ByteArrayInputStream(str.getBytes());
    ObjectInputStream ois = new ObjectInputStream(bis);
    w.readFields(ois);
    ois.close();
    return w;
  }

  /** {@inheritDoc} */
  @SuppressWarnings("unchecked")
  @Override
  public void readFields(DataInput in) throws IOException {
    // First clear the map. Otherwise we will just accumulate
    // entries every time this method is called.
    this.instance.clear();

    String inputStr = Text.readString(in);
    LOG.info("readFields inputStr: '" + inputStr + "'");
    String[] values = inputStr.split(VALUE_DELIMITER);

    // Get the number of entries in the map
    int entries = Integer.parseInt(values[0]);
    if (values.length != entries + 2) {
      return;
    }

    // Get key and value types of entries in the map
    String[] types = values[1].split(PAIR_DELIMITER, 2);
    Configuration conf = getConf();
    Writable key = (Writable) ReflectionUtils.newInstance(
        (Class<? extends Writable>) conf.getClass(types[0], Writable.class),
        conf);
    Writable value = (Writable) ReflectionUtils.newInstance(
        (Class<? extends Writable>) conf.getClass(types[1], Writable.class),
        conf);

    // Then read each key/value pair
    for (int i = 2; i < entries + 2; i++) {
      String[] keyValue = values[i].split(PAIR_DELIMITER, 2);

      key = stringToWritable(key, keyValue[0]);
      value = stringToWritable(value, keyValue[1]);
      instance.put(key, value);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void write(DataOutput out) throws IOException {

    // Write out the number of entries in the map
    String outputStr = instance.size() + VALUE_DELIMITER;

    // Then write out each key/value pair
    Iterator<Map.Entry<Writable, Writable>> it = instance.entrySet().iterator();
    for (int i = 0; it.hasNext(); i++) {
      Map.Entry<Writable, Writable> entry = it.next();

      // Set key and value types of entries in the map
      if (i == 0) {
        outputStr += entry.getKey().getClass().getName();
        outputStr += PAIR_DELIMITER;
        outputStr += entry.getValue().getClass().getName();
        outputStr += VALUE_DELIMITER;

      }
      outputStr += writableToString(entry.getKey());
      outputStr += PAIR_DELIMITER;
      outputStr += writableToString(entry.getValue());
      outputStr += VALUE_DELIMITER;
    }
    Text.writeString(out, outputStr);
    LOG.info("write outputStr: '" + outputStr + "'");
  }
}
