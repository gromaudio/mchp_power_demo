package com.gromaudio.powerbalancing;

class ConnectionPowerState {
        float w;
        float v;
        float a;

        public ConnectionPowerState() {
        }

        public ConnectionPowerState(float w, float v, float a) {
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
    }