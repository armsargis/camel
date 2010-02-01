/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dataformat.bindy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.camel.dataformat.bindy.annotation.DataField;
import org.apache.camel.dataformat.bindy.annotation.KeyValuePairField;
import org.apache.camel.dataformat.bindy.annotation.Link;
import org.apache.camel.dataformat.bindy.annotation.Message;
import org.apache.camel.dataformat.bindy.annotation.OneToMany;
import org.apache.camel.dataformat.bindy.annotation.Section;
import org.apache.camel.dataformat.bindy.format.FormatException;
import org.apache.camel.dataformat.bindy.util.Converter;
import org.apache.camel.spi.PackageScanClassResolver;
import org.apache.camel.util.ObjectHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The BindyKeyValuePairFactory is the class who allows to bind data of type key
 * value pair. Such format exist in financial messages FIX. This class allows to
 * generate a model associated to message, bind data from a message to the
 * POJOs, export data of POJOs to a message and format data into String, Date,
 * Double, ... according to the format/pattern defined
 */
public class BindyKeyValuePairFactory extends BindyAbstractFactory implements BindyFactory {

    private static final transient Log LOG = LogFactory.getLog(BindyKeyValuePairFactory.class);

    private Map<Integer, KeyValuePairField> keyValuePairFields = new LinkedHashMap<Integer, KeyValuePairField>();
    private Map<Integer, Field> annotedFields = new LinkedHashMap<Integer, Field>();
    private Map<String, Integer> sections = new HashMap<String, Integer>();

    private Map<String, List> lists = new HashMap<String, List>();

    private String keyValuePairSeparator;
    private String pairSeparator;
    private boolean messageOrdered;

    public BindyKeyValuePairFactory(PackageScanClassResolver resolver, String... packageNames) throws Exception {

        super(resolver, packageNames);

        // Initialize what is specific to Key Value Pair model
        initKeyValuePairModel();
    }

    /**
     * method uses to initialize the model representing the classes who will
     * bind the data This process will scan for classes according to the package
     * name provided, check the annotated classes and fields. Next, we retrieve
     * the parameters required like : Pair Separator & key value pair separator
     * 
     * @throws Exception
     */
    public void initKeyValuePairModel() throws Exception {

        // Find annotated KeyValuePairfields declared in the Model classes
        initAnnotedFields();

        // Initialize key value pair parameter(s)
        initMessageParameters();

    }

    public void initAnnotedFields() {

        for (Class<?> cl : models) {

            List<Field> linkFields = new ArrayList<Field>();

            for (Field field : cl.getDeclaredFields()) {
                KeyValuePairField keyValuePairField = field.getAnnotation(KeyValuePairField.class);
                if (keyValuePairField != null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Key declared in the class : " + cl.getName() + ", key : " + keyValuePairField.tag() + ", Field : " + keyValuePairField.toString());
                    }
                    keyValuePairFields.put(keyValuePairField.tag(), keyValuePairField);
                    annotedFields.put(keyValuePairField.tag(), field);
                }

                Link linkField = field.getAnnotation(Link.class);

                if (linkField != null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Class linked  : " + cl.getName() + ", Field" + field.toString());
                    }
                    linkFields.add(field);
                }
            }

            if (!linkFields.isEmpty()) {
                annotedLinkFields.put(cl.getName(), linkFields);
            }

        }
    }

    /**
     * 
     */
    public void bind(List<String> data, Map<String, Object> model, int line) throws Exception {

        Map<Integer, List> results = new HashMap<Integer, List>();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Key value pairs data : " + data);
        }

        // Separate the key from its value
        // e.g 8=FIX 4.1 --> key = 8 and Value = FIX 4.1
        ObjectHelper.notNull(keyValuePairSeparator, "Key Value Pair not defined in the @Message annotation");

        // Generate map of key value
        // We use a Map of List as we can have the same key several times
        // (relation one to many)
        for (String s : data) {

            // Get KeyValuePair
            String[] keyValuePair = s.split(getKeyValuePairSeparator());

            // Extract Key
            int key = Integer.parseInt(keyValuePair[0]);

            // Extract key value
            String value = keyValuePair[1];

            if (LOG.isDebugEnabled()) {
                LOG.debug("Key : " + key + ", value : " + value);
            }

            // Add value to the Map using key value as key
            if (!results.containsKey(key)) {

                List list = new LinkedList();
                list.add(value);
                results.put(key, list);

            } else {

                List list = (LinkedList)results.get(key);
                list.add(value);
            }

        }

        // Iterate over the model
        for (Class clazz : models) {

            Object obj = model.get(clazz.getName());

            if (obj != null) {

                // Generate model from key value map
                generateModelFromKeyValueMap(clazz, obj, results, line);

            }
        }

    }

    /**
     * @param clazz
     * @param obj
     * @param results
     * @param line
     * @throws Exception
     */
    private void generateModelFromKeyValueMap(Class clazz, Object obj, Map<Integer, List> results, int line) throws Exception {

        for (Field field : clazz.getDeclaredFields()) {

            field.setAccessible(true);

            KeyValuePairField keyValuePairField = field.getAnnotation(KeyValuePairField.class);

            if (keyValuePairField != null) {

                // Key
                int key = keyValuePairField.tag();

                // Get Value
                List<String> values = results.get(key);
                String value = null;

                // we don't received data
                if (values == null) {

                    /*
                     * The relation is one to one So we check if we are in a
                     * target class and if the field is mandatory
                     */
                    if (obj != null) {

                        // Check mandatory field
                        if (keyValuePairField.required() && values == null) {
                            throw new IllegalArgumentException("The mandatory key/tag : " + key + " has not been defined !");
                        }

                        Object result = getDefaultValueforPrimitive(field.getType());

                        try {
                            field.set(obj, result);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("Setting of field " + field + " failed for object : " + obj + " and result : " + result);
                        }

                    } else {

                        /*
                         * The relation is one to many So, we create an object
                         * with empty fields and we don't check if the fields
                         * are mandatory
                         */

                        // Get List from Map
                        List l = lists.get(clazz.getName());

                        if (l != null) {

                            // Test if object exist
                            if (!l.isEmpty()) {
                                obj = l.get(0);
                            } else {
                                obj = clazz.newInstance();
                            }

                            Object result = getDefaultValueforPrimitive(field.getType());
                            try {
                                field.set(obj, result);
                            } catch (Exception e) {
                                throw new IllegalArgumentException("Setting of field " + field + " failed for object : " + obj + " and result : " + result);
                            }

                            // Add object created to the list
                            if (!l.isEmpty()) {
                                l.set(0, obj);
                            } else {
                                l.add(0, obj);
                            }

                            // and to the Map
                            lists.put(clazz.getName(), l);

                            // Reset obj to null
                            obj = null;

                        } else {
                            throw new IllegalArgumentException("The list of values is empty for the following key : " + key + " defined in the class : " + clazz.getName());
                        }

                    } // end of test if obj != null

                } else {

                    // Data have been retrieved from message
                    if (values.size() >= 1) {

                        if (obj != null) {

                            // Relation OneToOne
                            value = (String)values.get(0);
                            Object result = null;

                            if (value != null) {

                                // Get pattern defined for the field
                                String pattern = keyValuePairField.pattern();

                                // Create format object to format the field
                                Format<?> format = FormatFactory.getFormat(field.getType(), pattern, keyValuePairField.precision());

                                // format the value of the key received
                                result = formatField(format, value, key, line);

                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("Value formated : " + result);
                                }

                            } else {
                                result = getDefaultValueforPrimitive(field.getType());
                            }
                            try {
                                field.set(obj, result);
                            } catch (Exception e) {
                                // System.out.println("Exception : " + e);
                                throw new IllegalArgumentException("Setting of field " + field + " failed for object : " + obj + " and result : " + result);
                            }

                        } else {

                            // Get List from Map
                            List l = lists.get(clazz.getName());

                            if (l != null) {

                                // Relation OneToMany
                                for (int i = 0; i < values.size(); i++) {

                                    // Test if object exist
                                    if ((!l.isEmpty()) && (l.size() > i)) {
                                        obj = l.get(i);
                                    } else {
                                        obj = clazz.newInstance();
                                    }

                                    value = (String)values.get(i);

                                    // Get pattern defined for the field
                                    String pattern = keyValuePairField.pattern();

                                    // Create format object to format the field
                                    Format<?> format = FormatFactory.getFormat(field.getType(), pattern, keyValuePairField.precision());

                                    // format the value of the key received
                                    Object result = formatField(format, value, key, line);

                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("Value formated : " + result);
                                    }

                                    try {
                                        if (value != null) {
                                            field.set(obj, result);
                                        } else {
                                            field.set(obj, getDefaultValueforPrimitive(field.getType()));
                                        }
                                    } catch (Exception e) {
                                        throw new IllegalArgumentException("Setting of field " + field + " failed for object : " + obj + " and result : " + result);
                                    }

                                    // Add object created to the list
                                    if ((!l.isEmpty()) && (l.size() > i)) {
                                        l.set(i, obj);
                                    } else {
                                        l.add(i, obj);
                                    }
                                    // and to the Map
                                    lists.put(clazz.getName(), l);

                                    // Reset obj to null
                                    obj = null;

                                }

                            } else {
                                throw new IllegalArgumentException("The list of values is empty for the following key : " + key + " defined in the class : " + clazz.getName());
                            }
                        }

                    } else {

                        // No values found from message
                        Object result = getDefaultValueforPrimitive(field.getType());

                        try {
                            field.set(obj, result);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("Setting of field " + field + " failed for object : " + obj + " and result : " + result);
                        }
                    }
                }
            }

            OneToMany oneToMany = field.getAnnotation(OneToMany.class);
            if (oneToMany != null) {

                String targetClass = oneToMany.mappedTo();

                if (!targetClass.equals("")) {
                    // Class cl = Class.forName(targetClass); Does not work in
                    // OSGI when class is defined in another bundle
                    Class cl = null;

                    try {
                        cl = Thread.currentThread().getContextClassLoader().loadClass(targetClass);
                    } catch (ClassNotFoundException e) {
                        cl = getClass().getClassLoader().loadClass(targetClass);
                    }

                    if (!lists.containsKey(cl.getName())) {
                        lists.put(cl.getName(), new ArrayList());
                    }

                    generateModelFromKeyValueMap(cl, null, results, line);

                    // Add list of objects
                    field.set(obj, lists.get(cl.getName()));

                } else {
                    throw new IllegalArgumentException("No target class has been defined in @OneToMany annotation !");
                }

            }

        }

    }

    /**
     * 
     */
    public String unbind(Map<String, Object> model) throws Exception {

        StringBuilder builder = new StringBuilder();

        Map<Integer, KeyValuePairField> keyValuePairFieldsSorted = new TreeMap<Integer, KeyValuePairField>(keyValuePairFields);
        Iterator<Integer> it = keyValuePairFieldsSorted.keySet().iterator();

        // Map containing the OUT position of the field
        // The key is double and is created using the position of the field and
        // location of the class in the message (using section)
        Map<Integer, String> positions = new TreeMap<Integer, String>();

        // Check if separator exists
        ObjectHelper.notNull(this.pairSeparator, "The pair separator has not been instantiated or property not defined in the @Message annotation");

        char separator = Converter.getCharDelimitor(this.getPairSeparator());

        if (LOG.isDebugEnabled()) {
            LOG.debug("Separator converted : '0x" + Integer.toHexString(separator) + "', from : " + this.getPairSeparator());
        }

        while (it.hasNext()) {

            KeyValuePairField keyValuePairField = keyValuePairFieldsSorted.get(it.next());
            ObjectHelper.notNull(keyValuePairField, "KeyValuePair is null !");

            // Retrieve the field
            Field field = annotedFields.get(keyValuePairField.tag());
            // Change accessibility to allow to read protected/private fields
            field.setAccessible(true);

            if (LOG.isDebugEnabled()) {
                LOG.debug("Tag : " + keyValuePairField.tag() + ", Field type : " + field.getType() + ", class : " + field.getDeclaringClass().getName());
            }

            // Retrieve the format, pattern and precision associated to the type
            Class<?> type = field.getType();
            String pattern = keyValuePairField.pattern();
            int precision = keyValuePairField.precision();

            // Create format
            Format format = FormatFactory.getFormat(type, pattern, precision);

            // Get object to be formatted
            Object obj = model.get(field.getDeclaringClass().getName());

            if (obj != null) {

                // Get field value
                Object keyValue = field.get(obj);

                if (this.isMessageOrdered()) {
                    // Generate a key using the number of the section
                    // and the position of the field
                    Integer key1 = sections.get(obj.getClass().getName());
                    Integer key2 = keyValuePairField.position();
                    Integer keyGenerated = generateKey(key1, key2);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Key generated : " + String.valueOf(keyGenerated) + ", for section : " + key1);
                    }

                    // Add value to the list if not null
                    if (keyValue != null) {

                        // Format field value
                        String valueFormated;

                        try {
                            valueFormated = format.format(keyValue);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("Formating error detected for the tag : " + keyValuePairField.tag(), e);
                        }

                        // Create the key value string
                        String value = keyValuePairField.tag() + this.getKeyValuePairSeparator() + valueFormated;

                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Value to be formatted : " + keyValue + ", for the tag : " + keyValuePairField.tag() + ", and its formated value : " + valueFormated);
                        }

                        // Add the content to the TreeMap according to the
                        // position defined
                        positions.put(keyGenerated, value);

                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Positions size : " + positions.size());
                        }
                    }
                } else {

                    // Add value to the list if not null
                    if (keyValue != null) {

                        // Format field value
                        String valueFormated;

                        try {
                            valueFormated = format.format(keyValue);
                        } catch (Exception e) {
                            throw new IllegalArgumentException("Formating error detected for the tag : " + keyValuePairField.tag(), e);
                        }

                        // Create the key value string
                        String value = keyValuePairField.tag() + this.getKeyValuePairSeparator() + valueFormated + separator;

                        // Add content to the stringBuilder
                        builder.append(value);

                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Value added : " + keyValuePairField.tag() + this.getKeyValuePairSeparator() + valueFormated + separator);
                        }
                    }
                }
            }
        }

        // Iterate through the list to generate
        // the message according to the order/position
        if (this.isMessageOrdered()) {

            Iterator<Integer> posit = positions.keySet().iterator();

            while (posit.hasNext()) {
                String value = positions.get(posit.next());

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Value added at the position (" + posit + ") : " + value + separator);
                }

                builder.append(value + separator);
            }
        }

        return builder.toString();
    }

    private Object formatField(Format format, String value, int tag, int line) throws Exception {

        Object obj = null;

        if (value != null) {

            // Format field value
            try {
                obj = format.parse(value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Parsing error detected for field defined at the tag : " + tag + ", line : " + line, e);
            }

        }

        return obj;

    }

    /**
     * Find the pair separator used to delimit the key value pair fields
     */
    public String getPairSeparator() {
        return pairSeparator;
    }

    /**
     * Find the key value pair separator used to link the key with its value
     */
    public String getKeyValuePairSeparator() {
        return keyValuePairSeparator;
    }

    /**
     * Flag indicating if the message must be ordered
     * 
     * @return boolean
     */
    public boolean isMessageOrdered() {
        return messageOrdered;
    }

    /**
     * Get parameters defined in @Message annotation
     */
    private void initMessageParameters() {
        if ((pairSeparator == null) || (keyValuePairSeparator == null)) {
            for (Class<?> cl : models) {
                // Get annotation @Message from the class
                Message message = cl.getAnnotation(Message.class);

                // Get annotation @Section from the class
                Section section = cl.getAnnotation(Section.class);

                if (message != null) {
                    // Get Pair Separator parameter
                    ObjectHelper.notNull(message.pairSeparator(), "No Pair Separator has been defined in the @Message annotation !");
                    pairSeparator = message.pairSeparator();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Pair Separator defined for the message : " + pairSeparator);
                    }

                    // Get KeyValuePair Separator parameter
                    ObjectHelper.notNull(message.keyValuePairSeparator(), "No Key Value Pair Separator has been defined in the @Message annotation !");
                    keyValuePairSeparator = message.keyValuePairSeparator();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Key Value Pair Separator defined for the message : " + keyValuePairSeparator);
                    }

                    // Get carriage return parameter
                    crlf = message.crlf();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Carriage return defined for the message : " + crlf);
                    }

                    // Get isOrderer parameter
                    messageOrdered = message.isOrdered();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Is the message ordered in output : " + messageOrdered);
                    }
                }

                if (section != null) {
                    // Test if section number is not null
                    ObjectHelper.notNull(section.number(), "No number has been defined for the section !");

                    // Get section number and add it to the sections
                    sections.put(cl.getName(), section.number());
                }
            }
        }
    }
}