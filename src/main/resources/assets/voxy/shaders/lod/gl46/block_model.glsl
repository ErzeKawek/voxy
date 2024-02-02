//TODO: FIXME: this isnt actually correct cause depending on the face (i think) it could be 1/64 th of a position off
// but im going to assume that since we are dealing with huge render distances, this shouldent matter that much
float extractFaceIndentation(uint faceData) {
    return float((faceData>>16)&63)/63;
}

vec4 extractFaceSizes(uint faceData) {
    return (vec4(faceData&0xF, (faceData>>4)&0xF, (faceData>>8)&0xF, (faceData>>12)&0xF)/16)+vec4(0,1f/16,0,1f/16);
}

uint faceHasAlphaCuttout(uint faceData) {
    return (faceData>>22)&1;
}

uint faceHasAlphaCuttoutOverride(uint faceData) {
    return (faceData>>23)&1;
}

bool modelHasBiomeLUT(BlockModel model) {
    return ((model.flagsA)&2) != 0;
}

bool modelIsTranslucent(BlockModel model) {
    return ((model.flagsA)&4) != 0;
}