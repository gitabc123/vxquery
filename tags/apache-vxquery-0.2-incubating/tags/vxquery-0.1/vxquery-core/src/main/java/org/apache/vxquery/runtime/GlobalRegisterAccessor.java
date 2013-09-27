/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.vxquery.runtime;

public final class GlobalRegisterAccessor<T> {
    private final int register;

    public GlobalRegisterAccessor(int register) {
        this.register = register;
    }

    @SuppressWarnings("unchecked")
    public T get(CallStackFrame frame) {
        return (T) frame.getGlobalRegisters().getValue(register);
    }

    @SuppressWarnings("unchecked")
    public T get(RegisterSet regs) {
        return (T) regs.getValue(register);
    }

    public void set(CallStackFrame frame, T value) {
        frame.getGlobalRegisters().setValue(register, value);
    }

    public void set(RegisterSet regs, T value) {
        regs.setValue(register, value);
    }
    
    public String toString() {
        return "G@" + register;
    }
}