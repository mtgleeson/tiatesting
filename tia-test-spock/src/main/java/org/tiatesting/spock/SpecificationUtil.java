package org.tiatesting.spock;

import org.spockframework.runtime.model.SpecInfo;

public class SpecificationUtil {

    public String getSpecName(SpecInfo spec){
        return spec.getPackage() + "." + spec.getName();
    }
}
