import sys
# Özgün 4 sesli ısınma korali (Do majör, 4/4, 8 ölçü). Tenor sekizlik sol anahtarında yazılır (transpose -1 oktav).
S=[("E",5),("D",5),("C",5),("C",5),("E",5),("F",5),("D",5),("D",5),("C",5),("B",4),("A",4),("G",4),("A",4),("B",4),("C",5)]
A=[("C",5),("B",4),("A",4),("A",4),("G",4),("A",4),("B",4),("B",4),("G",4),("G",4),("F",4),("E",4),("F",4),("G",4),("G",4)]
T=[("G",4),("G",4),("E",4),("F",4),("C",4),("D",4),("G",4),("F",4),("E",4),("E",4),("C",4),("C",4),("D",4),("D",4),("E",4)]
B=[("C",3),("G",2),("A",2),("F",2),("C",3),("D",3),("G",2),("G",2),("C",3),("E",3),("F",3),("C",3),("D",3),("G",2),("C",3)]
LY=["Hep","bir","a","ğız","dan,","bir","yü","rek","ten,","bir","lik","te","söy","le","riz."]
SYL=["single","single","begin","middle","end","single","begin","middle","end","begin","middle","end","begin","middle","end"]
parts=[("P1","Soprano","S.",S,"G",2,0),("P2","Alto","A.",A,"G",2,0),("P3","Tenor","T.",T,"G",2,-1),("P4","Bas","B.",B,"F",4,0)]
o=['<?xml version="1.0" encoding="UTF-8"?>','<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">','<score-partwise version="4.0">','<work><work-title>Isınma Korali</work-title></work>','<identification><creator type="composer">Nota Uyumu</creator></identification>','<part-list>']
for pid,name,ab,_,_,_,_ in parts: o.append(f'<score-part id="{pid}"><part-name>{name}</part-name><part-abbreviation>{ab}</part-abbreviation></score-part>')
o.append('</part-list>')
for pid,name,ab,notes,sign,line,octch in parts:
    o.append(f'<part id="{pid}">')
    idx=0
    for m in range(1,9):
        o.append(f'<measure number="{m}">')
        if m==1:
            o.append('<attributes><divisions>2</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time>')
            if octch: o.append(f'<transpose><diatonic>0</diatonic><chromatic>0</chromatic><octave-change>{octch}</octave-change></transpose>')
            o.append(f'<clef><sign>{sign}</sign><line>{line}</line>' + (f'<clef-octave-change>{octch}</clef-octave-change>' if octch else '') + '</clef></attributes>')
            if pid=="P1": o.append('<direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>72</per-minute></metronome></direction-type><sound tempo="72"/></direction>')
        n = 1 if m==8 else 2
        for k in range(n):
            st,oc=notes[idx]
            if octch: oc -= octch  # yazılı perde bir oktav yukarı
            dur = 8 if m==8 else 4
            typ = "whole" if m==8 else "half"
            o.append(f'<note><pitch><step>{st}</step><octave>{oc}</octave></pitch><duration>{dur}</duration><voice>1</voice><type>{typ}</type><lyric number="1"><syllabic>{SYL[idx]}</syllabic><text>{LY[idx]}</text></lyric></note>')
            idx+=1
        if m==8: o.append('<barline location="right"><bar-style>light-heavy</bar-style></barline>')
        o.append('</measure>')
    o.append('</part>')
o.append('</score-partwise>')
open(sys.argv[1],"w",encoding="utf-8").write("\n".join(o)+"\n")
