package com.gromaudio.powerbalancing;

class ConnectionPowerState {
        float w;
        float v;
        float a;
        boolean connected;

        public ConnectionPowerState() {
        }

        public ConnectionPowerState(boolean connected, float w, float v, float a) {
            this.connected = connected;
            this.w = w;
            this.v = v;
            this.a = a;
        }

        public float getW() {
            return w;
        }

        public void setW(float w) {
            this.w = w;
        }

        public float getV() {
            return v;
        }

        public void setV(float v) {
            this.v = v;
        }

        public float getA() {
            return a;
        }

        public void setA(float a) {
            this.a = a;
        }

        public boolean isConnected() {
            return connected;
        }

        public void setConnected(boolean connected) {
            this.connected = connected;
        }
}