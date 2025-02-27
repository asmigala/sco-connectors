package com.github.sco1237896.tools.maven.connector


import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.sco1237896.tools.maven.connector.support.CatalogConstants
import com.github.sco1237896.tools.maven.connector.support.CatalogSupport
import spock.lang.Specification

class Spec extends Specification {

    static ObjectNode adapter(String type, String mimeType) {
        return adapter(
            type,
            CatalogConstants.SOURCE == type ? CatalogConstants.OUT : CatalogConstants.IN,
            mimeType
        )
    }

    static ObjectNode adapter(String type, String inOut, String mimeType) {
        ObjectNode adapter = CatalogSupport.JSON_MAPPER.createObjectNode()

        adapter.with('metadata')
                .with('labels')
                .put('camel.apache.org/kamelet.type', type)

        adapter.with('spec')
                .with('types')
                .with(inOut)
                .put('mediaType', mimeType)

        return adapter
    }
}
